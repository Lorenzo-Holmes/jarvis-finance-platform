# JARVIS 金融投研平台前端

Vue 3 + Vite 金融研究终端。浏览器只访问 Java 主后端 `/api/**`；Python AI 服务只允许 Java 通过内部服务令牌调用，前端不直连 Python 或第三方行情源。

## 本地开发

```bash
cd frontend
npm install
npm run dev
```

默认访问 `http://localhost:5173/`。Vite 将 `/api/**` 代理到 Java `http://127.0.0.1:8200`。

## P0 测试与构建

```bash
npm run test:p0
npm run build
```

P0 测试使用 Node 20 内置 `node:test`，验证秒级行情 EventSource 解析/关闭行为，并扫描 `src/`，禁止浏览器代码出现 Python 8100、`/py` 内部路由或 AI/内部服务 Secret。CI 会在生产构建前执行该门禁。构建产物位于 `dist/`。

## 生产入口

生产前端使用 `https://f.shengxia.me`，API 统一访问 `https://agent.shengxia.me/api/**`。

认证使用 Java 写入的 HttpOnly JWT Cookie；浏览器写操作同时携带 Cookie-CSRF `X-XSRF-TOKEN`。不要在前端保存 JWT、AI API Key、GitHub Client Secret 或 Python 内部服务令牌。

## 实时行情

- 黄金 ETF、伦敦金、积存金：`GET /api/market/prices/stream` SSE 秒级推送。
- SSE 异常时行情页保留 30 秒 HTTP 轮询兜底。
- A 股、美股、Crypto：当前报价 1 秒刷新；K 线与交易时段维持低频刷新，避免每秒请求历史接口。
- 模拟盘订单票据复用秒级 SSE 报价；后端成交与风控同样优先使用秒级内存行情。

## 主要页面

```text
src/
├── pages/
│   ├── MarketPage.vue        # 黄金/伦敦金/积存金行情工作台
│   └── BacktestPage.vue      # 双均线可复现回测
├── components/
│   ├── CrossMarketView.vue   # A股/美股/Crypto 多市场终端
│   ├── SimTradeView.vue      # 模拟交易与风控状态
│   ├── AiCenter.vue          # AI 研究助手
│   ├── AdminView.vue         # 用户/配额/权限管理
│   └── LoginView.vue         # 登录/注册/密码重置
├── api/client.js             # 统一 Java API 客户端
└── composables/              # 图表、轮询、会话、freshness 等复用逻辑
```

## 技术栈

- Vue 3
- Vite 5
- ECharts 5
- SSE / EventSource
