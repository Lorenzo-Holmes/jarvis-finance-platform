# Grafana 监控模板

仓库提供了可直接在单机生产环境使用的 `../docker-compose.yml` 与
`../prometheus.yml`：Prometheus 监听 `127.0.0.1:9090`，Grafana 监听
`127.0.0.1:3000`，不会直接暴露公网端口。部署前在同目录创建权限为 `0600` 的
`grafana-admin-password`，再执行 `docker compose up -d`。

将 `provisioning/` 挂载到 Grafana 的 provisioning 目录，将 `dashboards/` 挂载到
`/var/lib/grafana/dashboards/jarvis`，并设置 `PROMETHEUS_URL`。

模板覆盖行情源失败/延迟/熔断切换、SSE、公开行情限流、采集器心跳和模拟交易风控指标。

同时提供 `jarvis-service-health.json`，覆盖 Java 后端存活、HTTP 4xx/5xx、P95 延迟、
Hikari 连接池、JVM 堆内存以及 `/api/agent`、`/api/ai` 请求速率。导入后请确认
Prometheus 已抓取 `jarvis-java` 或 `jarvis-backend` job；如果环境使用不同 job 名称，
需在该 dashboard 和 `deploy/monitoring/jarvis-alerts.yml` 中同步调整正则。

发布前校验：

```powershell
Get-ChildItem deploy/monitoring/grafana/dashboards/*.json | ForEach-Object {
  Get-Content $_.FullName -Raw | ConvertFrom-Json | Out-Null
}
```

生产验收至少打开两个 dashboard，确认时间范围内有数据，并在测试环境制造一次 5xx /
连接池等待后确认对应 Prometheus 规则进入 pending/firing；不要把“JSON 可解析”当作
Grafana 已经加载或告警已触发。
