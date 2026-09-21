# Implementation Plan

- [x] 1. 固化 Agent Run 与 Event 协议
  - 定义 runId、stepId、sequence、状态、时间和摘要字段。
  - 明确脱敏、长度限制和不可展示的敏感字段。
  - _Requirement: 1, 2, 3, 9_

- [x] 2. 重构 Java Agent Runtime
  - 用受控执行器替换裸线程和固定 sleep。
  - 增加只读工具注册表和工具执行边界。
  - 接入行情、技术指标、新闻、财报和风险服务。
  - _Requirement: 2, 7, 10_

- [x] 3. 接入认证、配额、权限、审计与取消
  - Agent 创建复用 JWT 和 AI 配额。
  - 取消信号停止后续工具和模型请求。
  - 记录运行状态和失败原因，敏感字段脱敏。
  - _Requirement: 3, 5, 6, 9_

- [x] 4. 提供 Agent Run API 与 SSE
  - 创建、流式订阅、运行详情、事件列表和取消接口。
  - 保留旧研究流兼容入口。
  - _Requirement: 1, 2, 5, 8_

- [x] 5. 重构 AI 中心 Trace UI
  - 使用真实 SSE 事件替换本地模拟步骤。
  - 实现计划、工具调用、结果、失败、取消和耗时展示。
  - 保留现有对话、证据栏、文件导入和 Markdown 输出。
  - _Requirement: 4, 7, 9_

- [x] 6. 增加历史运行与恢复交互
  - 运行列表、详情和事件重放 API 已接入 PostgreSQL；AI 中心提供历史抽屉、事件重放和现有运行重新订阅。
  - 服务重启时将遗留 RUNNING 标记为可恢复查看的失败运行，避免永久卡在执行中。
  - 失败重试与跳过已完成工具的幂等策略留待后续阶段；本阶段提供安全的历史重放与同一运行重订阅。
  - _Requirement: 5, 8_

- [x] 7. 自动化测试与契约校验
  - 覆盖后端运行生命周期、工具失败、取消、脱敏和权限。
  - 已通过 Flyway/Hibernate schema 契约、Agent 编排测试、前端 130 项 P0 测试、Playwright 财报导入门禁与生产构建；真实账号 Agent 浏览器成功流已通过。
  - _Requirement: 1–10_

- [ ] 8. 浏览器验收与发布门禁
  - [x] 真实生产账号下验证 AI 中心 Trace、工具生命周期、历史运行抽屉和 Markdown 结论。
  - [x] 用受控长连接验证前端停止状态从运行中更新为 `STOPPED`；生产后端实际取消由专项烟测覆盖。
  - [ ] 补充真实 `run_failed` 分支的浏览器失败提示验收。
  - [x] 构建、P0 测试、Java 测试通过后完成部署，并校验 GitHub Pages 版本 SHA。
  - _Requirement: 4, 5, 7, 8_
