#!/usr/bin/env bash
set -euo pipefail

SMOKE_ENV="${SMOKE_ENV:-/etc/jarvis/smoke.env}"
if [ -f "$SMOKE_ENV" ]; then
  set -a
  # shellcheck disable=SC1090
  source "$SMOKE_ENV"
  set +a
fi

SMOKE_API_BASE="${SMOKE_API_BASE:-https://agent.shengxia.me}"
LOCAL_API_BASE="${LOCAL_API_BASE:-http://127.0.0.1:8200}"
LOCAL_METRICS_URL="${LOCAL_METRICS_URL:-http://127.0.0.1:8201/actuator/prometheus}"
CHECK_PY_BLOCK="${CHECK_PY_BLOCK:-1}"
CHECK_AGENT_STREAM="${CHECK_AGENT_STREAM:-0}"
CHECK_AGENT_RECOVERY="${CHECK_AGENT_RECOVERY:-0}"
: "${SMOKE_EMAIL:?SMOKE_EMAIL is required}"
: "${SMOKE_PASSWORD:?SMOKE_PASSWORD is required}"

for cmd in curl python3; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: $cmd not found" >&2; exit 1; }
done

cookie_jar="$(mktemp)"
cleanup() { rm -f "$cookie_jar"; }
trap cleanup EXIT

curl_args=(--silent --show-error --fail-with-body --connect-timeout 5 --max-time 20)

assert_wrapped_ok() {
  local label="$1"
  local url="$2"
  local body
  body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200, d'
  echo "OK  $label"
}

assert_json_object() {
  local label="$1"
  local url="$2"
  local body
  body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert isinstance(d, dict) and len(d)>0, d'
  echo "OK  $label"
}

assert_price_stream() {
  local label="$1"
  local url="$2"
  local body
  # SSE 是长连接，max-time 到达时 curl 会返回 28；首包应已在响应正文中，因此允许该退出码。
  body="$(curl --silent --show-error --no-buffer --connect-timeout 5 --max-time 4 \
    -b "$cookie_jar" -c "$cookie_jar" "$url" 2>/dev/null || true)"
  printf '%s' "$body" | python3 -c 'import json,sys; lines=sys.stdin.read().splitlines(); payloads=[json.loads(x[5:].strip()) for x in lines if x.startswith("data:")]; assert payloads, "no SSE data frame"; d=payloads[0]; assert isinstance(d.get("market"),dict), d; assert isinstance(d.get("jd"),dict), d; assert d.get("server_time"), d'
  echo "OK  $label"
}

assert_reproducible_backtest() {
  local label="$1"
  local url="$2"
  local first second
  first="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  second="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$url")"
  FIRST="$first" SECOND="$second" python3 -c 'import json,os; a=json.loads(os.environ["FIRST"]); b=json.loads(os.environ["SECOND"]); assert a.get("code")==200 and b.get("code")==200,(a,b); x=a["data"]; y=b["data"]; assert x.get("strategy_version")=="double-ma-v1",x; fp=x.get("data_fingerprint",""); assert fp.startswith("sha256:") and len(fp)==71,fp; assert x.get("as_of"),x; assert fp==y.get("data_fingerprint"),(fp,y.get("data_fingerprint")); assert x.get("final_equity")==y.get("final_equity"),(x.get("final_equity"),y.get("final_equity"))'
  echo "OK  $label"
}

assert_agent_stream() {
  local label="$1"
  local url="$2"
  local csrf="$3"
  local body_file run_id events_body
  body_file="$(mktemp)"
  # Agent SSE 可能需要等待上游模型；这里只验证真实事件协议，不把模型内容打印到日志。
  set +e
  printf '%s' '{"question":"请用一句话确认 Agent 事件流已连通，不要调用交易工具。"}' | curl \
    --silent --show-error --http1.1 --no-buffer --connect-timeout 5 --max-time 90 \
    -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    --data-binary @- "$url" > "$body_file"
  stream_exit=$?
  set -e
  # SSE 完成后由反向代理主动关闭连接时，curl 可能返回 18/92；下面的
  # 事件解析仍必须通过，不能把传输层关闭码误判为业务失败。
  if [ "$stream_exit" -ne 0 ]; then
    echo "WARN  Agent SSE transport closed with curl=$stream_exit; validating buffered events"
  fi
  run_id="$(AGENT_BODY_FILE="$body_file" python3 -c 'import json,os,pathlib; lines=pathlib.Path(os.environ["AGENT_BODY_FILE"]).read_text().splitlines(); events=[json.loads(x[5:].strip()) for x in lines if x.startswith("data:")]; assert events, "no Agent SSE data"; ids={e.get("runId") for e in events}; assert len(ids)==1 and next(iter(ids)), ids; assert any(e.get("type")=="tool_call" for e in events), events; assert any(e.get("type") in {"run_completed","run_failed","run_cancelled"} for e in events), events[-1]; seq=[int(e.get("sequence",0)) for e in events]; assert seq==sorted(seq), seq; print(next(iter(ids)))')"
  echo "OK  $label"
  events_body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$SMOKE_API_BASE/api/agent/runs/$run_id/events")"
  AGENT_EVENTS="$events_body" python3 -c 'import json,os; d=json.loads(os.environ["AGENT_EVENTS"]); assert d.get("code")==200 and isinstance(d.get("data"),list) and d["data"], d'
  echo "OK  Agent PostgreSQL event replay"
  rm -f "$body_file"
}

assert_agent_cancel_and_reconnect() {
  local label="$1"
  local url="$2"
  local csrf="$3"
  local body_file run_id stream_pid cancel_body cancel_file cancel_code cancel_exit cancel_reason reconnect_file reconnect_exit
  body_file="$(mktemp)"
  # 让运行先落库并发出 run_started，再主动断开客户端连接；后端运行不能因此丢失。
  set +e
  printf '%s' '{"question":"请执行完整金融研究工作流，调用资讯、财报、行情、K线、技术指标和风险工具后再总结；不要执行交易。"}' | curl \
    --silent --show-error --http1.1 --no-buffer --connect-timeout 5 --max-time 90 \
    -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    --data-binary @- "$url" > "$body_file" 2>/dev/null &
  stream_pid=$!
  set -e

  run_id=""
  for _ in $(seq 1 20); do
    run_id="$(AGENT_BODY_FILE="$body_file" python3 -c 'import json,os,pathlib; p=pathlib.Path(os.environ["AGENT_BODY_FILE"]); events=[json.loads(x[5:].strip()) for x in p.read_text(errors="ignore").splitlines() if x.startswith("data:")]; print(next((e.get("runId") for e in events if e.get("runId")), ""))')"
    [ -n "$run_id" ] && break
    sleep 0.25
  done
  if [ -z "$run_id" ]; then
    kill "$stream_pid" 2>/dev/null || true
    wait "$stream_pid" 2>/dev/null || true
    rm -f "$body_file"
    echo "ERROR: Agent recovery runId not emitted" >&2
    exit 1
  fi

  # 模拟浏览器断线，之后使用同一个 runId 发送取消请求。
  kill "$stream_pid" 2>/dev/null || true
  wait "$stream_pid" 2>/dev/null || true
  echo "INFO  Agent recovery run started: canceling persisted run"
  cancel_file="$(mktemp)"
  set +e
  cancel_code="$(curl --silent --show-error --connect-timeout 5 --max-time 20 \
    -b "$cookie_jar" -c "$cookie_jar" -X DELETE \
    -H "X-XSRF-TOKEN: $csrf" \
    -o "$cancel_file" -w '%{http_code}' \
    "$SMOKE_API_BASE/api/agent/runs/$run_id")"
  cancel_exit=$?
  set -e
  if [ "$cancel_exit" -ne 0 ] || [ "$cancel_code" != "200" ]; then
    cancel_reason="$(python3 -c 'import json,pathlib,sys; d=json.loads(pathlib.Path(sys.argv[1]).read_text()); print(str(d.get("message") or d.get("error") or "")[:200])' "$cancel_file" 2>/dev/null || true)"
    echo "ERROR: Agent cancel failed http=$cancel_code curl=$cancel_exit reason=$cancel_reason" >&2
    rm -f "$body_file" "$cancel_file"
    exit 1
  fi
  cancel_body="$(python3 -c 'import pathlib,sys; print(pathlib.Path(sys.argv[1]).read_text() if pathlib.Path(sys.argv[1]).exists() else "")' "$cancel_file")"
  rm -f "$cancel_file"
  printf '%s' "$cancel_body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200,d'
  echo "OK  $label cancel request"

  # 重新订阅历史 SSE；必须看到同一 runId 的取消终态，而不是创建第二个运行。
  reconnect_file="$(mktemp)"
  set +e
  curl --silent --show-error --http1.1 --no-buffer --connect-timeout 5 --max-time 20 \
    -b "$cookie_jar" -c "$cookie_jar" \
    "$SMOKE_API_BASE/api/agent/runs/$run_id/stream" > "$reconnect_file" 2>/dev/null
  reconnect_exit=$?
  set -e
  AGENT_RECONNECT_FILE="$reconnect_file" AGENT_EXPECTED_RUN="$run_id" python3 -c 'import json,os,pathlib; expected=os.environ["AGENT_EXPECTED_RUN"]; events=[json.loads(x[5:].strip()) for x in pathlib.Path(os.environ["AGENT_RECONNECT_FILE"]).read_text(errors="ignore").splitlines() if x.startswith("data:")]; assert events, "no replay events"; assert {e.get("runId") for e in events}=={expected}, events; assert any(e.get("type")=="run_cancelled" for e in events), events[-1]'
  echo "OK  $label reconnect/replay (curl=$reconnect_exit)"
  rm -f "$body_file" "$reconnect_file"
}

post_wrapped_ok() {
  local label="$1"
  local url="$2"
  local csrf="$3"
  local payload="$4"
  local body
  body="$(printf '%s' "$payload" | curl "${curl_args[@]}" \
    -b "$cookie_jar" -c "$cookie_jar" \
    -H 'Content-Type: application/json' \
    -H "X-XSRF-TOKEN: $csrf" \
    --data-binary @- \
    "$url")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200, d'
  echo "OK  $label"
}

fetch_csrf_token() {
  local body
  body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$SMOKE_API_BASE/api/auth/csrf")"
  printf '%s' "$body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200; print(d["data"]["token"])'
}

echo "== Local readiness =="
assert_wrapped_ok "Java liveness" "$LOCAL_API_BASE/api/health/live"
assert_wrapped_ok "Java + DB readiness" "$LOCAL_API_BASE/api/health/ready"
metrics_body="$(curl "${curl_args[@]}" "$LOCAL_METRICS_URL")"
grep -Eq '^jarvis_market_stream_subscribers(\{[^}]*\})? ' <<<"$metrics_body" || {
  echo "ERROR: custom market telemetry metric missing" >&2
  exit 1
}
echo "OK  market telemetry"

echo "== Public edge =="
assert_wrapped_ok "public liveness" "$SMOKE_API_BASE/api/health/live"
assert_wrapped_ok "public readiness" "$SMOKE_API_BASE/api/health/ready"

if [ "$CHECK_PY_BLOCK" = "1" ]; then
  py_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
    --connect-timeout 5 --max-time 20 "$SMOKE_API_BASE/py/api/health")"
  [ "$py_status" = "404" ] || {
    echo "ERROR: public /py endpoint should be 404, got HTTP $py_status" >&2
    exit 1
  }
  echo "OK  public /py blocked"
fi

echo "== CSRF + auth =="
csrf_token="$(fetch_csrf_token)"
[ -n "$csrf_token" ] || { echo "ERROR: empty CSRF token" >&2; exit 1; }
echo "OK  CSRF token"

login_payload="$(SMOKE_EMAIL="$SMOKE_EMAIL" SMOKE_PASSWORD="$SMOKE_PASSWORD" python3 -c 'import json,os; print(json.dumps({"email":os.environ["SMOKE_EMAIL"],"password":os.environ["SMOKE_PASSWORD"]}))')"
unset SMOKE_PASSWORD
post_wrapped_ok "login" "$SMOKE_API_BASE/api/auth/login" "$csrf_token" "$login_payload"
assert_wrapped_ok "current user" "$SMOKE_API_BASE/api/auth/me"
assert_wrapped_ok "database detail" "$SMOKE_API_BASE/api/health/db"

echo "== Business reads =="
assert_wrapped_ok "market prices" "$SMOKE_API_BASE/api/market/prices"
assert_wrapped_ok "market overview" "$SMOKE_API_BASE/api/market/overview"
assert_wrapped_ok "daily market news" "$SMOKE_API_BASE/api/news/daily?limit=12&refresh=true&force=false"
assert_price_stream "1Hz market SSE" "$SMOKE_API_BASE/api/market/prices/stream"
assert_wrapped_ok "daily K-line" "$SMOKE_API_BASE/api/market/kline?market=gold_etf&interval=day&limit=5"
assert_wrapped_ok "sim account" "$SMOKE_API_BASE/api/sim/account"
assert_json_object "AI capabilities" "$SMOKE_API_BASE/api/ai/capabilities"
if [ "$CHECK_AGENT_STREAM" = "1" ]; then
  csrf_token="$(fetch_csrf_token)"
  assert_agent_stream "Agent SSE stream" "$SMOKE_API_BASE/api/agent/research/stream" "$csrf_token"
fi
if [ "$CHECK_AGENT_RECOVERY" = "1" ]; then
  csrf_token="$(fetch_csrf_token)"
  assert_agent_cancel_and_reconnect "Agent recovery" "$SMOKE_API_BASE/api/agent/research/stream" "$csrf_token"
fi
backtest_as_of="$(python3 -c 'import datetime; print((datetime.date.today()-datetime.timedelta(days=1)).isoformat())')"
assert_reproducible_backtest "reproducible backtest" "$SMOKE_API_BASE/api/backtest?market=gold_etf&short_ma=5&long_ma=20&initial_cash=100000&limit=60&as_of=$backtest_as_of"

# 登录后的会话可能在后续读接口中刷新 CSRF token，退出前重新获取，避免误报 419。
csrf_token="$(fetch_csrf_token)"
[ -n "$csrf_token" ] || { echo "ERROR: empty refreshed CSRF token" >&2; exit 1; }
post_wrapped_ok "logout" "$SMOKE_API_BASE/api/auth/logout" "$csrf_token" '{}'

echo "Smoke test PASSED: $SMOKE_API_BASE"
