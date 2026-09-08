# Grafana 监控模板

将 `provisioning/` 挂载到 Grafana 的 provisioning 目录，将 `dashboards/` 挂载到
`/var/lib/grafana/dashboards/jarvis`，并设置 `PROMETHEUS_URL`。

模板覆盖行情源失败/延迟/熔断切换、SSE、公开行情限流、采集器心跳和模拟交易风控指标。
