# JARVIS 需求完成度审计（2026-09-20）

本次审计基于 `doc/01_JARVIS金融投研平台_Software Requirement Specification_V1.0.md`、
`specs/financial-agent-workflow/`、`specs/market-chart-financial-import/` 以及部署文档。
文档中的视觉稿人工检查项与产品功能验收项分开统计，避免把视觉草图的 checklist 当成后端功能缺陷。

## 本次已补齐

- 管理员用户目录返回最近登录时间、GitHub/OAuth 登录来源和登录名。
- 增加管理员按用户查看审计事件的 API 与前端审计面板。
- 登录和 GitHub OAuth 成功后记录 `users.last_login_at`，新增 Flyway V13 迁移。
- 首页深度行情接入 Java 统一技术指标摘要：SMA5、SMA20、EMA12、RSI14、20 根支撑位和压力位。
- 回测返回夏普比率、已闭合交易胜率、盈亏比、平均持仓天数、逐点回撤曲线；前端增加指标卡和回撤图。
- 财报解析返回 `financial-report-v1` 结构化 JSON，固定包含核心结论、营收利润、盈利质量、资产负债、现金流、风险点、投资观点和待核验事项；缺失小节会标记 `needs_review`，不伪造数据。
- 前端财报页展示结构化摘要，并保留原始 Markdown 渲染结果。

## 自动化验证结果

- Java：全量 Maven 测试通过；包含 Flyway/Hibernate schema contract、回测、交易回滚故障注入、PostgreSQL 锁策略等测试。报告为 0 failures / 0 errors，PostgreSQL 未配置时仅保留既有跳过项。
- Python：`python -m pytest -p no:asyncio -q`，251 passed。普通 `pytest` 受本机 `pytest-asyncio` 与当前 pytest 版本兼容问题影响，代码测试本身不受影响。
- 前端：`npm run test:p0`，127 passed；`npm run build` 通过。
- 本地浏览器：预览模式可进入回测、财报解析页面；因预览模式无真实认证、行情和 Java/Python 联调数据，没有伪造结果执行真实回测或 Agent SSE。

## 仍未完成或需要真实环境验收

### P0 发布门禁

1. 用真实 PostgreSQL、JWT、行情源和 Python AI 服务完成一次 Agent Run 的 SSE 实流验收：计划、工具调用、失败/取消、断线后从 PostgreSQL 重放与重新订阅。
2. 用真实登录会话执行首页技术指标、回测高级指标、财报结构化返回和管理员 OAuth/审计查询的端到端验收。
3. 将前端生产构建发布到 GitHub Pages，并通过正式域名确认版本 SHA；将后端构建和 V13 迁移发布到远程服务器，再做 readiness/smoke 验收。
4. 财报导入页的组件级 replace/append、失败导入保留旧文本、选择文件不发分析请求等交互测试仍主要依靠源码回归，需要补充浏览器组件测试。

### P1/P2

- 流式 AI 响应的精确月度 Token usage 仍依赖上游稳定返回 usage 事件；当前非流式统计已完成。
- Grafana/Prometheus 的正式面板仍需在生产监控实例导入并验证 5xx、429、502、Hikari、行情源熔断等告警链路；仓库模板和指标基础已存在。
- 视觉规范文档中关于档案海景深、玻璃层次、长时间循环、移动端逐页像素审阅的 checklist 仍属于人工设计验收，不能用单元测试代替。
- 品牌 PNG 多尺寸资产与真实用户 Edge 的 GPU/无障碍最终人工验收仍需产品确认。

## 结论

核心业务功能缺口已经补齐并通过本地自动化测试；当前不能宣称“全部需求已生产验收完成”。剩余工作集中在真实环境联调、发布门禁、组件级浏览器测试和视觉人工确认，不应通过伪造数据或跳过认证来标记完成。
