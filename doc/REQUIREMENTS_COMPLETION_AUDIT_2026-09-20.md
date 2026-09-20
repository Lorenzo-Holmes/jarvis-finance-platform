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
- Agent 运行服务补齐 pending/running 孤儿运行恢复、终态运行内存清理、SSE 重连终态竞态保护和超大 payload 有界降级；新增生命周期/恢复边界回归测试。每个真实工具步骤现在按同一 `stepId` 发出 `step_started → tool_call → tool_result → step_completed`，并保留开始/结束时间与耗时，前端 Trace 已增加生命周期标签。
- 管理员按用户的额度、权限和审计能力已完成；新增 V14 用户组、成员关系、组级 AI 配额与组级功能权限，组策略作为无用户级覆盖时的共享默认策略，并提供管理员 API、审计事件和前端管理工作区。
- 流式 AI 请求会请求上游返回 usage chunk，并由 Java 解析 `total_tokens` 后计入用户或用户组月度 Token 配额；未返回 usage 的兼容上游仍保持响应可用。
- RSS 信息中心已补齐：Java/PostgreSQL 持久化 10 个预置来源、管理员新增/编辑/启停/可信度维护、用户来源/主题订阅和服务端订阅过滤；Python 抓取结果补充正文片段、来源分类、标签和可解释的规则影响方向，前端新增 RSS 资讯工作区与管理后台来源面板。
- RSS AI 分析闭环已补齐：Java 统一鉴权/配额/usage，Python 返回有界且不含思维链的摘要、关键词、情绪、风险等级、影响方向和关联市场；日报任务支持 `analyze` 参数并把分析写入 PostgreSQL 执行产物，模型失败时保留原 RSS/规则结果；中高风险资讯通过站内通知提醒，前端可跳转多市场。已在生产发布并通过每日资讯接口验收。
- RSS 抓取链路已补齐外部网络超时与并行抓取：单源连接/读取超时不会阻塞整条资讯接口，生产刷新新闻已从烟测超时恢复为通过。
- 测试 Agent 首版已补齐：`tools/test-agent/test-agent.mjs` 可读取 PRD 生成结构化验收用例，调用现有 Playwright 项目并输出脱敏缺陷报告；本地 managed Vite 环境的 smoke 运行通过。
- Grafana 服务健康面板和 Java 5xx/Hikari/目标存活告警模板已补齐；仍需要在生产 Prometheus/Grafana 实例导入后验证数据与 firing 状态。

## 自动化验证结果

- Java：全量 Maven 测试通过，555 tests / 0 failures / 0 errors / 5 skipped；包含 RSS AI 分析/日报产物/重要资讯提醒测试，以及既有 V15 Flyway/Hibernate schema contract、用户组配额/权限继承、回测、交易回滚故障注入、PostgreSQL 锁策略、Agent 工具步骤生命周期、运行恢复、SSE 断线取消竞态等测试。PostgreSQL 未配置时仅保留既有跳过项。
- Python：`PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest -q`，259 passed。普通 `pytest` 仍受本机 `pytest-asyncio` 与当前 pytest 版本兼容问题影响，代码测试本身不受影响。
- 前端：`npm run test:p0`，130 passed；`npm run build` 通过。
- 浏览器：Playwright `financial-import` 项目通过，验证财报 Markdown 文件导入替换/追加、失败导入保留原文，以及选择文件不会提前请求分析接口；预览模式下夜间多市场图表和财报输入面板也已实际检查。显式分析因本地 Java 未启动，在 CSRF 阶段按预期失败。未伪造真实回测或 Agent SSE 结果。
- 测试 Agent：`node tools/test-agent/test-agent.mjs --run --project smoke` 在本地 managed Vite 环境通过，6 条 smoke 浏览器用例无失败；未把 smoke 结果冒充真实登录、真实 AI 或生产验收。

## Gitee PR 审核（2026-09-20）

- Gitee 当前没有 open PR；最新 PR#15（`feat: 补 DAILY_DIGEST 执行器，定时任务 4 个类型全部可用`）已处于 merged 状态，提交 `3db902a` 已包含日报执行器、参数边界、来源不可用降级和 wiring 测试。本轮复核 PR 详情、文件差异和评论后，没有可再次修改或合并的待审 PR。
- Agent 生命周期修复提交 `0ffc751`、生产烟测门禁提交 `f344f0d` 已同步到 Gitee `main` 和 GitHub `main`；后续新增 PR 仍应先做 diff/测试审查，再合并到 `main`。

## 生产发布验收（2026-09-21 更新）

- GitHub Pages 工作流 `Deploy Frontend to GitHub Pages` 运行 `35519407536` 成功；正式域名 `https://f.shengxia.me/version.json` 返回 SHA `44321ced1bc0751b57c182b947ed933fffdb1ffa`。
- 后端 release `20260921-0ffc751` 已通过远端原子切换；`/opt/jarvis/current` 指向该 release，旧版本 `20260920-cedb9de` 保留用于回滚。发布包中的 Java/Python/前端资源 SHA256 校验全部通过。
- 远端 `jarvis-ai.service`、`jarvis-java.service`、`postgresql` 均为 active；Java readiness、Python 内部 token readiness、Flyway v14 和公网 Java readiness 均返回成功。
- 公网数据库健康接口返回 401（该接口受认证保护），属于预期安全行为。
- 使用真实生产烟测账号完成：登录、数据库详情、行情、1Hz 行情 SSE、日 K、模拟盘、AI capabilities、Agent SSE、Agent PostgreSQL 事件回放、可复现回测和退出登录均通过；Agent SSE 的代理连接关闭码已按事件终态校验处理，不影响业务事件完整性。
- 2026-09-21 05:35 运行升级后的 `CHECK_AGENT_STREAM=1 CHECK_AGENT_RECOVERY=1` 专项烟测：除 Agent 真实工作流、`tool_call`、PostgreSQL 事件回放、断线取消/重订阅和回测登出外，烟测脚本还强制验证每个运行中工具步骤的 `step_started → tool_call → tool_result → step_completed` 顺序、共享 `stepId` 与终态事件；全部通过，此前可复现的断线取消 409 已不再出现。
- 使用真实生产会话对 `/api/news/analyze` 提交一条资讯联调通过，返回 `code=200`、1 条结构化分析并带有模型标识；未输出模型正文或任何凭据。

## 仍未完成或需要真实环境验收

### P0 发布门禁

1. Agent Run 的真实 SSE、JWT、工具调用、步骤生命周期、主动取消、客户端断线后的重新订阅以及 PostgreSQL 事件回放已通过生产专项烟测；取消竞态修复已发布。仍需补真实登录会话下的浏览器 Trace/停止按钮验收。
2. 首页技术指标、回测高级指标和财报结构化返回已用真实登录会话完成 API 级生产联调；管理员 OAuth/审计查询仍缺真实管理员凭据下的端到端验收。
3. Agent 中心的真实 Trace、停止、失败提示、Markdown 结论仍缺真实登录会话下的浏览器验收；后端事件协议和生产 SSE 已通过，但当前 Playwright 门禁尚未覆盖真实 Agent SSE。

### P1/P2

- 流式 AI 响应的精确月度 Token usage 仍依赖上游稳定返回 usage 事件；当前非流式统计已完成。
- PRD V1.2 的 RSS 信息源管理、10 源配置、用户订阅、AI 分析、重要事件提醒和多市场入口已发布；生产每日自动更新、通知实际触发和上游模型返回 usage 仍需真实业务数据验收。
- 测试 Agent 已实现首版；仍需在具备真实账号的环境执行 auth/Agent 专项浏览器用例，不能只用无后端 smoke 作为最终门禁。
- Grafana/Prometheus 的仓库模板已补齐服务健康、5xx、Hikari、JVM、Agent/API 请求面板和目标存活/5xx/连接池告警；仍需在生产监控实例导入并验证 5xx、429/502、Hikari、行情源熔断等告警链路。
- 视觉规范文档中关于档案海景深、玻璃层次、长时间循环、移动端逐页像素审阅的 checklist 仍属于人工设计验收，不能用单元测试代替。
- 品牌 PNG 多尺寸资产与真实用户 Edge 的 GPU/无障碍最终人工验收仍需产品确认。

## 结论

核心业务功能已持续补齐，RSS AI/资讯链路和 Agent 的真实工具调用、取消、断线重订阅成功流已发布并完成生产烟测；当前仍不能宣称“全部需求已生产验收完成”。剩余工作集中在真实账号下的 Agent 浏览器 Trace/停止/失败/Markdown 交互、管理员 OAuth/审计完整验收、RSS 通知与 usage 的真实业务触发、测试 Agent 专项账号用例、监控告警导入以及视觉人工确认，不应通过伪造数据或跳过认证来标记完成。
