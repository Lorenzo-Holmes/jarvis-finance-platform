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

echo "== Local readiness =="
assert_wrapped_ok "Java liveness" "$LOCAL_API_BASE/api/health/live"
assert_wrapped_ok "Java + DB readiness" "$LOCAL_API_BASE/api/health/ready"
metrics_body="$(curl "${curl_args[@]}" "$LOCAL_METRICS_URL")"
printf '%s' "$metrics_body" | grep -q '^jarvis_market_stream_subscribers ' || {
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
csrf_body="$(curl "${curl_args[@]}" -b "$cookie_jar" -c "$cookie_jar" "$SMOKE_API_BASE/api/auth/csrf")"
csrf_token="$(printf '%s' "$csrf_body" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d.get("code")==200; print(d["data"]["token"])')"
[ -n "$csrf_token" ] || { echo "ERROR: empty CSRF token" >&2; exit 1; }
echo "OK  CSRF token"

login_payload="$(SMOKE_EMAIL="$SMOKE_EMAIL" SMOKE_PASSWORD="$SMOKE_PASSWORD" python3 -c 'import json,os; print(json.dumps({"email":os.environ["SMOKE_EMAIL"],"password":os.environ["SMOKE_PASSWORD"]}))')"
unset SMOKE_PASSWORD
post_wrapped_ok "login" "$SMOKE_API_BASE/api/auth/login" "$csrf_token" "$login_payload"
assert_wrapped_ok "current user" "$SMOKE_API_BASE/api/auth/me"
assert_wrapped_ok "database detail" "$SMOKE_API_BASE/api/health/db"

echo "== Business reads =="
assert_wrapped_ok "market prices" "$SMOKE_API_BASE/api/market/prices"
assert_price_stream "1Hz market SSE" "$SMOKE_API_BASE/api/market/prices/stream"
assert_wrapped_ok "daily K-line" "$SMOKE_API_BASE/api/market/kline?market=gold_etf&interval=day&limit=5"
assert_wrapped_ok "sim account" "$SMOKE_API_BASE/api/sim/account"
assert_json_object "AI capabilities" "$SMOKE_API_BASE/api/ai/capabilities"
backtest_as_of="$(python3 -c 'import datetime; print((datetime.date.today()-datetime.timedelta(days=1)).isoformat())')"
assert_reproducible_backtest "reproducible backtest" "$SMOKE_API_BASE/api/backtest?market=gold_etf&short_ma=5&long_ma=20&initial_cash=100000&limit=60&as_of=$backtest_as_of"

post_wrapped_ok "logout" "$SMOKE_API_BASE/api/auth/logout" "$csrf_token" '{}'

echo "Smoke test PASSED: $SMOKE_API_BASE"
