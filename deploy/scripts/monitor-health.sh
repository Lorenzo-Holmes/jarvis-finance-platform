#!/usr/bin/env bash
set -euo pipefail

JAVA_READY_URL="${JAVA_READY_URL:-http://127.0.0.1:8200/api/health/ready}"
JAVA_METRICS_URL="${JAVA_METRICS_URL:-http://127.0.0.1:8201/actuator/prometheus}"
PYTHON_READY_URL="${PYTHON_READY_URL:-http://127.0.0.1:8100/api/ready}"
PYTHON_ENV="${PYTHON_ENV:-/etc/jarvis/python.env}"
DISK_PATH="${DISK_PATH:-/}"
DISK_MIN_FREE_PERCENT="${DISK_MIN_FREE_PERCENT:-15}"
POSTGRES_BACKUP_DIR="${POSTGRES_BACKUP_DIR:-/var/backups/jarvis/postgres}"
POSTGRES_BACKUP_MAX_AGE_HOURS="${POSTGRES_BACKUP_MAX_AGE_HOURS:-30}"
REQUIRE_POSTGRES_BACKUP="${REQUIRE_POSTGRES_BACKUP:-true}"
MONITOR_STATE_DIR="${MONITOR_STATE_DIR:-/var/lib/jarvis-monitor}"
ALERT_WEBHOOK_URL="${ALERT_WEBHOOK_URL:-}"

mkdir -p "$MONITOR_STATE_DIR"
STATE_FILE="$MONITOR_STATE_DIR/last-state.sha256"

problems=()

if ! curl --silent --show-error --fail --max-time 8 "$JAVA_READY_URL" >/dev/null; then
  problems+=("Java/DB readiness 失败")
fi
metrics_body="$(curl --silent --show-error --fail --max-time 8 "$JAVA_METRICS_URL" 2>/dev/null || true)"
if [ -z "$metrics_body" ]; then
  problems+=("Java Prometheus management endpoint 失败")
elif ! printf '%s' "$metrics_body" | grep -q '^jarvis_market_stream_subscribers '; then
  problems+=("Java 自定义行情指标缺失")
else
  metric_value() {
    local metric="$1"
    local selector="${2:-}"
    if [ -n "$selector" ]; then
      printf '%s\n' "$metrics_body" | awk -v metric="$metric" -v selector="$selector" '
        index($0, metric "{") == 1 && index($0, selector) > 0 { print $NF; exit }
      '
    else
      printf '%s\n' "$metrics_body" | awk -v metric="$metric" '$1 == metric { print $2; exit }'
    fi
  }

  for collector in core jd; do
    age="$(metric_value jarvis_market_poll_last_run_age_seconds "collector=\"$collector\"")"
    if [ -z "$age" ]; then
      problems+=("行情采集 heartbeat 指标缺失: $collector")
    elif ! awk -v value="$age" 'BEGIN { exit !(value <= 20) }'; then
      problems+=("行情采集 heartbeat 超时: ${collector}=${age}s")
    fi
  done

  subscribers="$(metric_value jarvis_market_stream_subscribers)"
  broadcast_age="$(metric_value jarvis_market_stream_last_broadcast_age_seconds)"
  if [ -n "$subscribers" ] && [ -n "$broadcast_age" ] \
      && awk -v value="$subscribers" 'BEGIN { exit !(value > 0) }' \
      && ! awk -v value="$broadcast_age" 'BEGIN { exit !(value <= 15) }'; then
    problems+=("SSE 行情广播 heartbeat 超时: ${broadcast_age}s (${subscribers} subscribers)")
  fi
fi

PYTHON_SERVICE_TOKEN=""
if [ -f "$PYTHON_ENV" ]; then
  # 只在子 shell 中读取 env，再提取内部令牌，避免把 AI_API_KEY 等变量导入监控进程环境。
  PYTHON_SERVICE_TOKEN="$(
    set -a
    # shellcheck disable=SC1090
    source "$PYTHON_ENV"
    printf '%s' "${PYTHON_SERVICE_TOKEN:-}"
  )"
fi
if [ -z "$PYTHON_SERVICE_TOKEN" ]; then
  problems+=("PYTHON_SERVICE_TOKEN 无法读取")
elif ! curl --silent --show-error --fail --max-time 8 \
    -H "X-Internal-Service-Token: $PYTHON_SERVICE_TOKEN" \
    "$PYTHON_READY_URL" >/dev/null; then
  problems+=("Python AI readiness 失败")
fi
unset PYTHON_SERVICE_TOKEN

if ! [[ "$DISK_MIN_FREE_PERCENT" =~ ^[0-9]+$ ]] || [ "$DISK_MIN_FREE_PERCENT" -lt 1 ] || [ "$DISK_MIN_FREE_PERCENT" -gt 99 ]; then
  problems+=("DISK_MIN_FREE_PERCENT 配置非法")
else
  used_percent="$(df -P "$DISK_PATH" 2>/dev/null | awk 'NR==2 {gsub("%", "", $5); print $5}')"
  if ! [[ "$used_percent" =~ ^[0-9]+$ ]]; then
    problems+=("无法读取磁盘使用率: $DISK_PATH")
  else
    free_percent=$((100 - used_percent))
    if [ "$free_percent" -lt "$DISK_MIN_FREE_PERCENT" ]; then
      problems+=("磁盘剩余 ${free_percent}% < ${DISK_MIN_FREE_PERCENT}% ($DISK_PATH)")
    fi
  fi
fi

if [ "$REQUIRE_POSTGRES_BACKUP" = "true" ]; then
  latest_backup=""
  if [ -d "$POSTGRES_BACKUP_DIR" ]; then
    latest_backup="$(find "$POSTGRES_BACKUP_DIR" -maxdepth 1 -type f -name '*.dump' -printf '%T@ %p\n' 2>/dev/null \
      | sort -nr | head -n 1 | cut -d' ' -f2- || true)"
  fi
  if [ -z "$latest_backup" ]; then
    problems+=("未找到 PostgreSQL 备份: $POSTGRES_BACKUP_DIR")
  else
    backup_mtime="$(stat -c %Y "$latest_backup" 2>/dev/null || echo 0)"
    now="$(date +%s)"
    age_hours=$(((now - backup_mtime) / 3600))
    if [ "$age_hours" -gt "$POSTGRES_BACKUP_MAX_AGE_HOURS" ]; then
      problems+=("PostgreSQL 最新备份已过期: ${age_hours}h > ${POSTGRES_BACKUP_MAX_AGE_HOURS}h")
    fi
  fi
fi

if systemctl is-failed --quiet jarvis-postgres-backup.service 2>/dev/null; then
  problems+=("jarvis-postgres-backup.service 最近一次执行失败")
fi

if [ "${#problems[@]}" -eq 0 ]; then
  status="OK"
  message="JARVIS production health recovered/healthy"
else
  status="ERROR"
  message="$(printf '%s; ' "${problems[@]}")"
  message="${message%; }"
fi

# 数字（备份年龄、磁盘百分比等）变化不应被视作新故障类型，避免持续故障重复告警。
fingerprint_input="$(printf '%s|%s' "$status" "$message" | sed -E 's/[0-9]+/N/g')"
fingerprint="$(printf '%s' "$fingerprint_input" | sha256sum | awk '{print $1}')"
previous="$(cat "$STATE_FILE" 2>/dev/null || true)"

send_webhook() {
  local text="$1"
  [ -n "$ALERT_WEBHOOK_URL" ] || return 0
  local payload
  payload="$(python3 -c 'import json,sys; t=sys.argv[1]; print(json.dumps({"text":t,"content":t}, ensure_ascii=False))' "$text")"
  curl --silent --show-error --fail --max-time 10 \
    -H 'Content-Type: application/json' \
    --data "$payload" \
    "$ALERT_WEBHOOK_URL" >/dev/null
}

if [ "$fingerprint" != "$previous" ]; then
  notified=1
  if [ "$status" = "ERROR" ]; then
    echo "JARVIS MONITOR ERROR: $message" >&2
    if ! send_webhook "[JARVIS][ERROR] $message"; then
      echo "WARN: webhook 发送失败，将在下一次监控重试" >&2
      notified=0
    fi
  else
    echo "JARVIS MONITOR OK: $message"
    if [ -n "$previous" ] && ! send_webhook "[JARVIS][RECOVERED] $message"; then
      echo "WARN: recovery webhook 发送失败，将在下一次监控重试" >&2
      notified=0
    fi
  fi
  if [ "$notified" -eq 1 ]; then
    printf '%s\n' "$fingerprint" > "$STATE_FILE"
  fi
fi

if [ "$status" = "ERROR" ]; then
  exit 1
fi
