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
- Agent 运行服务补齐 pending/running 孤儿运行恢复、终态运行内存清理、SSE 重连终态竞态保护和超大 payload 有界降级；新增生命周期/恢复边界回归测试。
- 管理员按用户的额度、权限和审计能力已完成；新增 V14 用户组、成员关系、组级 AI 配额与组级功能权限，组策略作为无用户级覆盖时的共享默认策略，并提供管理员 API、审计事件和前端管理工作区。
- 流式 AI 请求会请求上游返回 usage chunk，并由 Java 解析 `total_tokens` 后计入用户或用户组月度 Token 配额；未返回 usage 的兼容上游仍保持响应可用。

## 自动化验证结果

- Java：全量 Maven 测试通过，545 tests / 0 failures / 0 errors / 5 skipped；包含 V14 Flyway/Hibernate schema contract、用户组配额/权限继承、回测、交易回滚故障注入、PostgreSQL 锁策略、Agent 生命周期恢复等测试。PostgreSQL 未配置时仅保留既有跳过项。
- Python：release 构建使用 `PYTEST_DISABLE_PLUGIN_AUTOLOAD=1 python -m pytest -q`，238 passed。普通 `pytest` 仍受本机 `pytest-asyncio` 与当前 pytest 版本兼容问题影响，代码测试本身不受影响。
- 前端：`npm run test:p0`，128 passed；`npm run build` 通过。
- 浏览器：Playwright `financial-import` 项目通过，验证财报 Markdown 文件导入替换/追加、失败导入保留原文，以及选择文件不会提前请求分析接口；预览模式下夜间多市场图表和财报输入面板也已实际检查。显式分析因本地 Java 未启动，在 CSRF 阶段按预期失败。未伪造真实回测或 Agent SSE 结果。

## Gitee PR 审核（2026-09-20）

- Gitee 当前没有 open PR；最新 PR#15（`feat: 补 DAILY_DIGEST 执行器，定时任务 4 个类型全部可用`）已处于 merged 状态，提交 `3db902a` 已包含日报执行器、参数边界、来源不可用降级和 wiring 测试。本轮已复核 PR 详情、文件差异和评论，没有可再次修改或合并的待审 PR。
- 本地当前分支已同步 Gitee `main`；后续新增 PR 应继续先做 diff/测试审查，再合并到 `main`。

## 生产发布验收（2026-09-20）

- GitHub Pages 工作流 `Deploy Frontend to GitHub Pages` 运行 `35514631730` 成功；正式域名 `https://f.shengxia.me/version.json` 返回 SHA `857635b4ad0548fbf316a8d986d54bd9d7a964b5`。
- 后端 release `20260920-857635b-final2` 已通过远端原子切换；`/opt/jarvis/current` 指向该 release，旧版本 `20260920-6fe8f83-final` 保留用于回滚。
- 远端 `jarvis-ai.service`、`jarvis-java.service`、`postgresql` 均为 active；Java readiness、Python 内部 token readiness、Flyway v14 和公网 Java readiness 均返回成功。
- 公网数据库健康接口返回 401（该接口受认证保护），属于预期安全行为。

## 仍未完成或需要真实环境验收

### P0 发布门禁

1. 用真实 PostgreSQL、JWT、行情源和 Python AI 服务完成一次 Agent Run 的 SSE 实流验收：计划、工具调用、失败/取消、断线后从 PostgreSQL 重放与重新订阅。代码级恢复、重放、孤儿运行和取消边界已经覆盖，仍缺真实账号/上游联调证据。
2. 用真实登录会话执行首页技术指标、回测高级指标、财报结构化返回和管理员 OAuth/审计查询的端到端验收。
3. Agent 中心的真实 Trace、停止、失败提示、Markdown 结论仍缺真实登录会话下的浏览器验收；当前 Playwright 门禁已覆盖财报导入，但尚未覆盖真实 Agent SSE。

### P1/P2

- 流式 AI 响应的精确月度 Token usage 仍依赖上游稳定返回 usage 事件；当前非流式统计已完成。
- PRD V1.2 的 RSS “可配置至少 10 个来源、用户订阅与信息流关联”目前已有 Python 内部 RSS 存储、抓取、去重、日报摘要和 Java 定时任务，但来源管理/订阅的完整用户-facing 页面及生产数据验收仍需补齐。
- PRD V1.2 的“测试 Agent”（从 PRD 生成用例、自动执行浏览器测试并输出缺陷报告）尚未实现；现有 Playwright 是测试脚手架，不等同于测试 Agent。
- Grafana/Prometheus 的正式面板仍需在生产监控实例导入并验证 5xx、429、502、Hikari、行情源熔断等告警链路；仓库模板和指标基础已存在。
- 视觉规范文档中关于档案海景深、玻璃层次、长时间循环、移动端逐页像素审阅的 checklist 仍属于人工设计验收，不能用单元测试代替。
- 品牌 PNG 多尺寸资产与真实用户 Edge 的 GPU/无障碍最终人工验收仍需产品确认。

## 结论

核心业务功能缺口已经补齐并完成本次前后端生产发布；当前不能宣称“全部需求已生产验收完成”。剩余工作集中在真实账号下的 Agent SSE/断线恢复和业务端到端验收、财报导入组件级浏览器测试、监控告警导入以及视觉人工确认，不应通过伪造数据或跳过认证来标记完成。
