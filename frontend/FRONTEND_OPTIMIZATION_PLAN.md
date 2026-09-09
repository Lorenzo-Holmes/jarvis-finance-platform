# JARVIS 金融投研平台 · 前端完整优化 Plan

> 目标：从“AI Dashboard”转向“金融研究终端 / 交易工作台”。设计优先级依次为：信息密度 > 数据可读性 > 操作效率 > 品牌识别 > 装饰效果。

## 1. 设计原则

### 1.1 视觉语言
- 背景：中性深灰 / 石墨灰，不使用蓝紫宇宙背景、霓虹光晕。
- 品牌色：低饱和金色，只用于品牌、选中态、主要操作，不用于大面积填充。
- 涨跌：红 / 绿只表达市场方向、风险和状态，不承担品牌功能。
- 圆角：控制在 3–6px；金融终端避免大量 12–20px 卡片圆角。
- 阴影：默认不用发光阴影；层级主要依赖边框、明度和留白。
- 数字：统一 tabular-nums，主要价格和 PnL 保证视觉对齐。

### 1.2 信息架构
- 高频信息必须首屏可见。
- 控件靠近其作用对象，减少“页面标题一块、控制区一块、内容一块”的分散布局。
- 行情类页面优先“报价条 + 主图 + 指标/明细”，少用纵向超大 Card 堆叠。
- AI 是能力而不是视觉主题；研究助手只在真正需要生成内容的场景出现。

---

## 2. P0：立即修复 / 已开始实施

### 2.1 品牌图标
- [x] 使用透明背景的金色 J + K 线 + 上升曲线图标。
- [x] 顶部品牌、登录页、浏览器 favicon 切换到新图标。
- [ ] 后续补齐同源 PNG：32 / 64 / 192 / 512px，用于旧浏览器与 PWA / Apple Touch Icon。

### 2.2 多市场 K 线挤在左侧
根因：`CrossMarketView` 在 `v-show` 隐藏状态下初始化 ECharts，初始容器宽度接近 0；切换到“多市场”后没有主动 resize。

处理：
- [x] Tab 切换后主动 `resize()`。
- [x] 加 `ResizeObserver`，容器尺寸变化后自动重算图表。
- [x] 默认聚焦最近 60 根 K 线，避免 120 根全部压缩。
- [x] Y 轴移动到右侧，更接近 TradingView / 交易终端习惯。
- [x] 日期标签缩短、图例紧凑、DataZoom 降低高度。

验收：
- 1920 / 1440 / 1280 宽度切入“多市场”时 K 线必须填满图表区域。
- 不需要手动拖动窗口才能恢复图表宽度。
- A 股 120 根日 K 默认可清晰辨认最近约 60 根。

### 2.3 行情页信息密度
原问题：4 个报价占用两整行，3 张 420px 图表纵向堆叠，页面首屏有效信息少。

处理：
- [x] 4 个实时价格合并成 4 列紧凑报价条。
- [x] 黄金 ETF / 伦敦金 K 线改为桌面端双栏。
- [x] 京东积存金图表占完整下一行。
- [x] 图表高度从 420px 收敛到约 310px。
- [x] 页面最大内容宽度提升至 1580px，减少超宽屏无效留白。

验收：
- 1440px 屏幕首屏应看到 4 个核心报价 + 至少两张主要 K 线的大部分内容。
- 单个价格 Card 不再超过必要高度。

### 2.4 公共入口与工作台导航
- [x] 未登录入口从单一登录 Card 改为简洁产品首屏，登录 / 注册 / 重置收敛到按需弹层。
- [x] 首屏使用轻量 SVG 行情预览与 CSS 动效表达研究终端能力，不引入额外图片或动画依赖。
- [x] 公开入口只保留一条主价值说明、两个一级动作和三项能力标签，避免营销文案堆叠。
- [x] 桌面工作台改为左侧研究导航，1080px 以下自动回退为横向 Tab，保持原有键盘与 ARIA 行为。
- [x] 新增动效继续受全局 `prefers-reduced-motion` 约束。

---

## 3. P1：行情工作台重构

### 3.1 首页行情布局
建议最终结构：

1. 顶部报价矩阵：黄金 ETF、伦敦金、浙商积存金、民生积存金。
2. 主工作区：左侧主 K 线（约 70%），右侧技术指标 / 数据摘要（约 30%）。
3. 标的通过顶部标签切换，而不是长期同时展示 3 张完整 K 线。
4. 次级图表可在“对比模式”展开为双栏。

推荐交互：
- 默认只展示一个主图，保证主图可读性。
- `黄金ETF / 伦敦金 / 积存金` 使用 segment / text tabs 切换。
- `1m / 5m / 15m / 30m / 1h / 日K` 使用快捷周期按钮，不再全部依赖 select。
- “60 / 120 / 250 根”移到更多设置，不作为一级操作。

### 3.2 行情摘要
新增紧凑指标：
- 今开、昨收、最高、最低。
- 振幅、涨跌幅。
- 当前价格相对 SMA20 / EMA12 / 支撑 / 阻力位置。
- 数据源与最后更新时间。
- 数据陈旧状态（例如 >60s 时显示 stale）。

### 3.3 当前实现进度
- [x] 行情页改为单主图 + 右侧报价/数据状态 Rail。
- [x] 黄金ETF / 伦敦金 / 积存金使用一级标的切换，不再长期同时渲染三张大图。
- [x] 周期改为快捷按钮，样本数量降级为次级 Select。
- [x] 顶部报价条可直接点击切换主图标的。
- [x] 主图统一右侧 Y 轴、时间轴缩放和最近约 80 根默认视窗。
- [ ] SMA / EMA / 支撑阻力摘要等待统一指标接口后接入行情首页 Rail。

### 3.4 图表统一规范
创建 `useEcharts` / `useMarketChart` composable：
- 初始化、dispose、ResizeObserver 统一处理。
- Tooltip、Axis、DataZoom、字体、主题配置统一处理。
- 每个页面不再复制一套 ECharts option。
- 涨跌颜色按中国市场 / 国际市场定义统一，不允许页面自行硬编码。

---

## 4. P1：多市场页面

建议从当前单列页面演进为：

### 桌面布局
- 左：自选 / 市场标的列表（220–280px）。
- 中：当前标的 K 线主图。
- 右：报价详情 + 技术指标 + 研究解读（280–340px）。

### 当前实现进度
- [x] 桌面端三栏：标的目录 / 主图 / 报价与研究 Rail。
- [x] A 股 / 美股 / Crypto 一级文本 Tab。
- [x] 标的名称 / 代码搜索。
- [x] 周期快捷按钮。
- [x] 技术指标与研究解读迁移到右侧 Rail。
- [x] 980px 以下 Rail 自动下沉，700px 以下单栏。

### 关键功能
- A 股 / 美股 / Crypto 作为一级市场切换。
- 标的支持搜索，不只使用长 select。
- 周期使用快捷按钮。
- 技术指标支持折叠，不侵占主图空间。
- 研究解读放右侧 Drawer / Rail，不再单独占整块页面高度。
- 当前标的名称、代码、币种、数据源、更新时间保持在同一视觉区域。

---

## 5. P1：回测页面

当前进度：
- [x] 左侧策略参数面板 + 右侧结果主区。
- [x] 参数即时校验：短均线、长均线、本金。
- [x] 策略收益 / Buy & Hold / 超额收益并列展示。
- [x] 净值主图和近期交易表重新组织。
- [ ] 最大回撤独立曲线、Sharpe、胜率等等待后端指标支持。

当前问题：高级绩效指标仍需后端补齐。

目标布局：
- 左侧策略参数面板（固定宽度 280px）。
- 右侧主区域：收益指标 + 净值 / 回撤图。
- 底部：交易明细表。

新增：
- 参数校验与短均线 < 长均线的即时提示。
- 策略收益 vs 买入持有对比。
- 最大回撤曲线。
- Sharpe、胜率、盈亏比、持仓时间等核心指标（后端支持后接入）。
- 最近多次回测结果快速比较。

---

## 6. P1：模拟盘

当前进度：
- [x] 顶部账户资产条。
- [x] 四标的紧凑 Market Tape。
- [x] 左侧 Order Ticket + 右侧持仓。
- [x] 下单名义金额 / 资金占用 / 融资比例实时预估。
- [x] 成交记录独立底部区域。
- [x] 高杠杆提示放入订单上下文。

目标：更像 Order Ticket，而不是普通表单。

布局：
- 顶部账户资产条：总资产 / 可用现金 / 浮盈亏 / 仓位。
- 左侧下单票据：标的、方向、数量、杠杆、估算成交金额。
- 中部持仓。
- 右侧风险提示 / 保证金 / 杠杆状态。
- 底部成交与委托记录。

交互：
- 买 / 卖必须具有明确但克制的红绿语义。
- 杠杆按钮避免彩色渐变。
- 高风险操作需要二次确认，但不使用弹窗泛滥。

---

## 7. P1：研究助手

当前进度：
- [x] 页面改为“研究工具箱 + 研究会话”。
- [x] 行情、财报、产业链能力放入左侧研究工具箱。
- [x] 对话气泡改为研究记录式 message rows。
- [x] 快捷问题改为研究模板栏。
- [x] 引擎状态降级为工作台状态信息，不再作为主视觉。

原则：把 AI 从“产品主题”降级为“研究能力”。

调整：
- 页面名称保持“研究助手”。
- 左侧上下文：当前标的、行情、策略、上传材料。
- 右侧研究对话。
- 快捷问题采用研究模板，而非气泡式 AI suggestion chips。
- 输出支持结构化区块：结论 / 风险 / 数据依据 / 待验证项。
- 如果后端未来支持来源，增加来源与时间戳，避免只显示大段生成文本。

---

## 8. P1：Admin / 运维

当前进度：
- [x] Admin 改为用户目录 + 账户详情 + 配额 / 权限控制区。
- [x] 用户列表增加角色与启用状态视觉标识。
- [x] 账户详情展示 ID、角色、注册时间、权限数量。
- [x] 运维从状态 Card 改为 Service Health 表格。
- [x] 运维增加统一总体状态和立即检查入口。
- [ ] GitHub username / OAuth 来源需要后端 Admin DTO 增加数据后接入。
- [ ] 审计记录需要后端查询 API 后接入。

### Admin
- 入口仅对 `role=ADMIN` 用户展示；后端继续强制 `/api/admin/**` ADMIN 权限。
- 搜索支持邮箱、昵称，并建议支持 GitHub username。
- 用户列表增加角色 badge、账号来源、注册时间。
- 详情操作拆分：账户 / 配额 / 功能权限 / 审计记录。
- 角色变更和禁用账户需明确反馈。

### 运维
- 由“卡片状态页”改为表格式 Service Health。
- 展示 API 延迟、最近成功时间、错误率（后端有数据后接入）。
- 正常状态不发光；异常才提高视觉优先级。

---

## 9. P2：前端工程结构

当前进度：
- [x] `App.vue` 从页面业务聚合层缩成应用壳，目前约 80 行。
- [x] 行情页拆到 `pages/MarketPage.vue`。
- [x] 回测页拆到 `pages/BacktestPage.vue`。
- [x] 顶部品牌 / 用户区拆为 `components/common/AppHeader.vue`。
- [x] 主导航拆为 `components/common/AppTabs.vue`。
- [x] 行情报价矩阵拆为 `components/market/QuoteStrip.vue`。
- [x] 多市场标的目录拆为 `components/market/InstrumentList.vue`。
- [x] 模拟盘账户条拆为 `components/trading/AccountStrip.vue`。
- [x] 模拟盘行情 Tape 拆为 `components/trading/MarketTape.vue`。
- [x] 模拟盘订单票据拆为 `components/trading/OrderTicket.vue`。
- [x] 模拟盘持仓表拆为 `components/trading/PositionsTable.vue`。
- [x] 模拟盘成交记录拆为 `components/trading/TradeHistory.vue`。
- [x] 多市场右侧 Rail 拆为 `QuoteDetail / TechnicalSummary / ResearchRail`。
- [x] `DataState.vue` 统一核心页面 Loading / Error / Empty / Retry 表现。
- [x] `useEcharts.js` 统一 ECharts 初始化、ResizeObserver 与 dispose。
- [x] `useMarketChart.js` 统一 K 线、成交量、均线叠加、坐标轴和 DataZoom。
- [x] `usePolling.js` 统一页面轮询启动 / 停止与卸载清理。
- [x] `useAuthSession.js` 统一登录态恢复、登录接管与退出。
- [x] `useWorkspaceTabs.js` 统一 Tab 权限、访问记录与回退逻辑。
- [x] `useLatestRequest.js` 防止快速切换标的 / 周期时旧请求覆盖新状态。
- [x] `useFreshness.js` 统一数据新鲜度和 stale 判断。
- [x] `utils/formatters.js` 统一数字 / 百分比格式化。
- [x] `usePolling.js` 在浏览器 Tab 隐藏时自动暂停，恢复可见后立即补一次同步。
- [ ] 避免为了“目录完整”制造无价值的薄 wrapper 页面；继续按真实复杂度决定是否迁移到 `pages/`。
- [ ] 当 CrossMarket / Research / Admin 出现更多路由级能力时再迁入 `pages/`，当前优先保持组件边界有实际职责。

当前结构：

```text
src/
  pages/
    MarketPage.vue
    BacktestPage.vue
  components/
    common/
      AppHeader.vue
      AppTabs.vue
      DataState.vue
    market/
      QuoteStrip.vue
      InstrumentList.vue
      QuoteDetail.vue
      TechnicalSummary.vue
      ResearchRail.vue
    trading/
      AccountStrip.vue
      MarketTape.vue
      OrderTicket.vue
      PositionsTable.vue
      TradeHistory.vue
    CrossMarketView.vue
    SimTradeView.vue
    AiCenter.vue
    OpsView.vue
    AdminView.vue
  composables/
    useAuthSession.js
    useWorkspaceTabs.js
    useEcharts.js
    useMarketChart.js
    usePolling.js
    useLatestRequest.js
    useFreshness.js
  utils/
    formatters.js
```

注意：当前页面数量不多，不为了架构形式引入 Pinia 或 Vue Router。只有当 URL 深链、跨页面共享状态、页面历史导航成为真实需求时再引入。

---

## 10. P2：性能

### 当前重点
- ECharts chunk 约 537KB，仍超过 Vite 默认 500KB warning。

当前进度：
- [x] 未登录状态不再提前加载行情数据与 ECharts。
- [x] 多市场 / 模拟盘 / 研究助手 / 运维 / Admin 改为进入 Tab 时按需挂载。
- [x] 页面轮询统一通过 `usePolling` 在卸载时自动停止。
- [x] ECharts 实例统一通过 `useEcharts` 在卸载时自动 dispose，并响应容器尺寸变化。
- [x] 首页行情与多市场共用 `useMarketChart`，移除重复图表配置代码。
- [x] 首页实时报价、积存金报价和模拟盘数据使用 30 秒级刷新。
- [x] 主入口 JS 目前约 84KB raw / 34KB gzip，低于既定 120KB gzip 目标。
- [x] ECharts DataZoom / Legend 改为更细粒度组件注册，约从 543.16KB / 181.77KB gzip 降至 537.40KB / 179.94KB gzip。
- [x] 已评估 ECharts warning：剩余体积主要来自图表核心能力，当前作为异步 chunk 不阻塞主入口，不为消除 warning 强行替换图表库。

计划：
- 检查 `charts/echarts.js` 是否真正按需注册图表 / 组件。
- 行情图表仅在对应页面首次进入时加载。
- 对 Admin / Ops / Research 保持 async component。
- 避免隐藏页面持续轮询；只对当前活动页面进行高频刷新。
- 页面切换后恢复轮询，后台页面降低刷新频率。

目标：
- 主入口 gzip JS < 120KB（ECharts 独立异步 chunk 不计）。
- 非行情页面首次加载不下载 ECharts。
- 页面切换无明显 layout jump。

---

## 11. P2：响应式

断点建议：
- >= 1280：完整研究终端布局。
- 900–1279：双栏收敛，右侧 Rail 可下沉。
- 600–899：单栏图表，报价 2 列。
- < 600：报价单列 / 横向滚动；工具栏分组；表格允许横向滚动。

禁止：
- 通过单纯缩小字体解决移动端问题。
- K 线图被压缩到小于约 280px 高度。

---

## 12. P2：可用性 / 状态规范

所有请求统一具备：
- [x] 核心行情 / 多市场 / 模拟盘 Loading state。
- [x] 核心行情 / 多市场 / 模拟盘 Error + Retry state。
- [x] 核心行情 / 多市场 Empty state。
- [x] 行情 / 多市场 Stale state（90 秒无成功同步提示）。
- [x] 行情 / 多市场 Last updated / 本地同步时间。
- [x] Admin 用户目录 / 详情加载状态统一，空搜索结果使用 DataState。
- [x] Research 行情摘要 / 财报 / 产业链工具补齐 Loading / Error / Retry 状态。

控件统一：
- [x] hover / focus-visible / disabled。
- [x] 主 Tab 支持方向键 / Home / End 键盘导航并补齐 ARIA tab 语义。
- [x] 所有普通按钮明确 `type="button"`，避免嵌入 form 后误触发提交。
- [x] 登录页表单 label 关联、研究会话 log、操作反馈 aria-live / role 状态补齐。
- [x] `prefers-reduced-motion` 支持。
- [x] Select、Input、Button 维持 32–36px 桌面密度。
- [x] 交易表格 / 运维健康表 header sticky（长表场景）。

---

## 13. 实施顺序

### Sprint A — 可见体验
1. 新品牌图标。
2. 多市场 K 线尺寸修复。
3. 行情页密度改造。
4. 控件尺寸和页面宽度统一。

### Sprint B — 页面工作台化
1. 行情页主图 + 右侧指标 Rail。
2. 多市场三栏工作区。
3. 回测两栏布局。
4. 模拟盘 Order Ticket。

### Sprint C — 工程与性能
1. 抽离图表 composable。
2. 拆分 App.vue。
3. 活动页面轮询策略。
4. ECharts bundle 优化。
5. 响应式和可访问性回归。

---

## 14. 最终验收标准

- 不再出现蓝紫渐变 / 霓虹发光 / 大量胶囊按钮。
- 1440px 首屏有效信息量显著高于旧版。
- 任意 Tab 首次打开图表尺寸正确，无需窗口 resize。
- 行情价格、涨跌、时间、来源层级清晰。
- 所有核心页面风格一致，不存在“首页新风格、子页面旧 AI 风”。
- Admin 仍由后端角色校验保护，不通过前端隐藏逻辑代替权限校验。
- `npm run build` 无错误。
