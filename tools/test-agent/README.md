# JARVIS 测试智能体

把需求文档转成可追踪的验收用例，并可调用仓库现有的 Playwright 用例生成脱敏缺陷报告。

```powershell
# 只读 PRD 并生成用例/报告，不启动浏览器
node tools/test-agent/test-agent.mjs --prd doc/DeepSeek大模型金融投研系统-PRD.md

# 在已启动前端的环境执行 smoke，并将结果合并到报告
node tools/test-agent/test-agent.mjs --run --project smoke --base-url https://f.shengxia.me

# 在已配置 E2E_USER_EMAIL / E2E_USER_PASSWORD 的真实环境执行 Agent 专项
node tools/test-agent/test-agent.mjs --run --project agent --base-url https://f.shengxia.me
```

输出目录默认是 `.test-agent/latest/`，其中 `test-cases.json` 是结构化用例，
`defect-report.md` 是评审报告。该工具不生成、保存或打印账号密码、Cookie、Token、API Key；
真实登录仍由 `E2E_USER_*` / `E2E_ADMIN_*` 环境变量提供，Playwright 登录态目录也不入库。
