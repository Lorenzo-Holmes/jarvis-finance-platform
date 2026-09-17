# 交接：Phase 2  统一计算口径 + 线上后端更新

> 写于 `369672d`。此前工作都在同一会话里完成，上下文将尽，故把**已验证的事实**、
> **剩余配方**与**踩过的坑**固化在这里，避免下一位重新摸索。
> 凡是我没读到的地方都明确标注，不要把我的转述当权威。

## 1. 当前状态（可直接核对）

- `main = 369672d`，Gitee 与 GitHub 13/13 分支一致，工作区干净
- 测试：Java **330**、Python **158**、前端 **90** 全绿
- 线上：`jarvis-remote`（`43.111.49.27`，见 `~/.ssh/config`）三服务 `active`，
  当前 release `/opt/jarvis/releases/20260915-093931-a21da31`（2026-09-15），**明显落后**

## 2.  已完成的部分（风险面，可作模板）

证据链是**三层**，缺一层都不算迁完：

1. **数值一致**：`java-backend/src/test/java/com/jarvis/research/ai/RiskMetricsTest.java`
   与 `backend/tests/test_risk_metrics_contract.py` 用同一组向量独立推导期望值，
   两端逐字一致（10 个向量）
2. **接线正确**：`backend/tests/test_analyze_risk_java_metrics.py` 用"一调用就失败"的替身
   证明 `ai_service` 真的没再自算本地指标
3. **换来源不改响应**：同向量下"引用 Java 指标"与"本地自算"的响应 **`==`**

改动落点：
- Java：`AiController.enrichRiskBody`（用 `loadServerOwnedCloses` 的**同一次取数**结果
  算出 `RiskMetrics`，作为 `metrics` 附进下发 payload）
- Python：`ai_service.analyze_risk(..., metrics=None)` 有则**复制后引用**（既有实现会
  `pop("alerts")`，直接引用会改到调用方对象）；`ai_routes.RiskReq` 加可选 `metrics` 字段

## 3.  剩余配方（quote / trend / chat）——**注意量级，与风险面不同**

**关键前提（已核实，勿低估）**：Java 侧原有 `MarketMetrics` 与 `RiskMetrics`；`QuoteMetrics`
已在 quote 面移植完成（见下方进度），所以 quote 剩下的只是接线与测试。**trend 至今没有**
对应实现（`com/jarvis/research/ai/` 包已逐个文件确认；`market/` 包只有 `QuoteDTO`，那是 DTO
不是算法）。剩余要迁的是 `research_tools.kline_metrics`（约 130 行，另含 `_sma/_ema/_rsi`
与 `trend_forecast`——注意 `smart_quote` 里也在用它算预测区间，所以 trend 面会同时影响
quote 的 forecast 字段）。

**进度（截至 3aa189b）**
- 风险面：三层证据链闭合（`c5fcb4a` `b3f5b77` 数值一致 → `3cee824` `c9facf7` 接线 → `369672d` 等价性）
- quote 面：计算 `2fdc09b`、接线 `3aa189b`、三层测试（同批）——**已完成**
- *更正*：本文档此前把 `quote_metrics` 估成"约 80 行"是**错的**，它实际只有 25 行，
  多算的是紧随其后的 `_sma/_ema/_rsi`。这个估计曾让人以为 quote 面很重，实际一轮就做完了

**quote 接入点的一处坑（已踩过）**：`/trend` 与 `/quote` 两条路由的尾部代码**完全同形**
（都收尾于 `horizon_days/confidence/symbol`），改 `/quote` 时必须用 `price_data` 作唯一锚点。
这也正是 trend 面下一步的接入点。

所以每个面的**第一步是把计算移植到 Java**（沿用既有格式化字符串契约：金额 6 位小数、
百分比 4 位小数、`ROUND_HALF_UP`、`toPlainString`），**之后**才是接线四步：

1. 移植计算 → `QuoteMetrics` / `TrendMetrics`，配跨语言同向量测试（做法同 `RiskMetricsTest`
   与 `test_risk_metrics_contract.py`：期望值**独立推导**，不要抄实现输出）
2. 该 hook（`enrichQuoteBody` / `enrichTrendBody`）用**同一次取数**的结果算出指标 → 附进 payload
3. Python 对应函数（quote 入口在 `ai_service.py:400` 一带）增加可选 `metrics` 参数：
   有则**复制后**引用（既有实现有 `pop("alerts")` 这类就地修改，直接引用会改到调用方对象），
   无则回退本地
4. 补三层测试（数值一致 / 接线正确 / 换来源不改响应）

chat 面**放到最后**：它的 `deterministic_context` 同时依赖 quote 与 kline 两套指标
（`research_tools.py:259`、`:264`），要等那两个面都就位。

**修订（第 31 轮，推翻上面这一行）**：原计划"全迁完后删 Python 侧本地自算与跨语言契约
测试"经查调用图后**不成立，不执行**：

- 每个面只被一个路由调用、且都已传 `metrics`，但**直连 Python 的调用方**、
  以及 Java 侧 `marketDataService == null` 的守卫路径，都让本地分支**仍然可达**。
  删掉它会让这些路径静默出错
- 本地实现与 Java 实现已由七套契约测试钉成逐值相等，**漂移可被发现**；
  删掉那份契约测试等于拆掉唯一的防漂移护栏
- 因此 ⑧ 的收口不是"删代码"，而是：**Java 在有数据时是唯一数值来源，Python 引用之；
  回退分支保留，但被契约钉死**

## 3.1  完成状态（第 32 轮）

- 五个计算单元全部移植到 Java：`RiskMetrics` / `QuoteMetrics` / `KlineMetrics` /
  `TrendForecast` + `MarketTrend` / `PortfolioMetrics`，外加编排层 `DeterministicContext`
- 四个面全部接通"Java 算、Python 引用"：**risk / quote / trend / chat**
  （组合面无需单独接线——`portfolio_metrics` 只被 `deterministic_context` 调用，
  而它只经 chat 路径到达）
- 每面三层证据：一调用就失败的替身证明没自算；路由能收字段；同输入下换来源响应逐字相等
- Java 侧另有 `AiControllerMetricsWiringTest` 钉住"确实发出去了"以及 K 线转 Map 的**键名**
  （键名写错时 Python 读到空值但不报错，只会悄悄分叉）
- 判据按面而异，且都写进了注释：risk/trend 看 `available` 是否存在，quote/chat 看字典
  是否非空——因为 `quote_metrics` 与 chat 上下文**都没有** `available` 字段

## 4. 部署配方（**部分未经我核对**）

- 已验证：SSH 可连、三服务 `active`、release 目录结构、`deploy/scripts/` 脚本齐备
- `server-preflight.sh` 是**只读**预检，先跑它
- 常规更新＝本地构建 → 上传 → `promote-release.sh`（会重启 `jarvis-java`/`jarvis-ai`）→ 健康检查
- **构建机＝本地**（已核实，脚本原文）：`build-release.sh` 注明"在构建机执行，输出一个
  可上传到 `/opt/jarvis/releases/<id>` 的目录"。它依次跑 **Java 全量测试 + `package` +
  `repackage`**、构建 migration jar、跑 **Python pytest**，产物落到
  `dist/releases/<时间戳>-<sha12>/`（含 `java-backend/app.jar`、`java-backend/migration.jar`、
  `backend/app`）→ **构建通过即等于测试通过**，这是发布前的天然闸门
- **脚本在服务器上的位置**（已核实）：`/opt/jarvis/build/<sha>/deploy/scripts/`——
  发布是由服务器上那份**完整代码镜像**编排的，不在 release 目录里
- 服务器目录布局：`/opt/jarvis/{build,current,releases,staging,venv}`；
  `staging/` 里是 2026-09-07 的**前端**暂存，而前端已改走 GitHub Pages（`§0`），属陈旧残留
- **已核实（第 32 轮读脚本原文）**：`promote-release.sh` 收**一个位置参数**
  （`/opt/jarvis/releases/<release-id>`），切 symlink 后先重启 `jarvis-ai` 再重启
  `jarvis-java`，各自健康检查（8100 `/api/ready`、8200 `/api/health/ready`），
  **任一失败自动回滚旧 symlink 并重启旧版本**——发布本身是自保护的
- 前置条件（脚本自检）：release 必须含 `java-backend/app.jar`、`backend/app/main.py`、
  `backend/requirements.txt`；且服务器须有 `/etc/jarvis/java.env`、`/etc/jarvis/python.env`
- `build-release.sh` 原文：从仓库根执行 `mvn clean test package` → migration jar →
  `cd backend && pytest` → 前端构建，产物落 `dist/releases/<STAMP>-<sha12>/`。
  Windows 上要用 Git Bash 并覆盖 `PYTHON=python`（脚本默认 `python3`）、
  `JAVA_HOME` 用 Unix 形式路径。**从 `backend/` 目录跑 pytest 已验证同样 218 全过**
- 部署目标主机（**SSH 记录**）：`~/.ssh/config` 里的 **`jarvis-remote`** → `43.111.49.27`
  （User `root`，`IdentityFile ~/.ssh/id_ed25519`）。上传即
  `scp -r dist/releases/<id> root@43.111.49.27:/opt/jarvis/releases/`
- 服务器上 `/opt/jarvis/build/<完整sha>/` 是**上一次发布的完整代码镜像**，
  当前为 `4f6108c84bac364ec83ef2d202867dd1c258e403`
- `migrate-production-ubuntu.sh` 同 `run-h2-migration.sh` 属**历史迁移**用途，勿混入常规发布
- **不要混入** `run-h2-migration.sh`：那是 H2 → PostgreSQL 的**一次性数据迁移**
  （目标表必须为空、逐表核对行数），与代码发布无关，误用后果严重

### 4.1 已核实：线上确实缺研究任务链路（只读探查）

- 线上 release `/opt/jarvis/releases/20260915-093931-a21da31`，`app.jar` 日期 2026-09-15
- **带对照组的 grep**：`AiController=2`、`MarketDataService=8`（对照组，证明检查方法有效），
  而 `ResearchController=0`、`ResearchTask=0` → 该能力**不在线上构建里**。
  也就是说这次发布交付的是**一个缺失的能力**，不是版本号刷新
- **不要用 HTTP 状态码判断接口是否存在**：`/api/research/tasks` 返回 **401 而非 404**，
  但那是 Spring Security 在路由**之前**就拦截的结果——**不存在的路径同样 401**，
  它证明不了任何事。要判断"线上有没有这个功能"，请用上面那种**带对照组的 jar 检查**
- **不要用 `unzip -l ... | grep -c` 做交叉验证**：服务器上未必装了 unzip，
  工具缺失时它恒返回 0，那是**空验证**（我踩过一次，差点把它当成旁证）
- SSH 提示：PowerShell 会把内层双引号吃掉再传给 ssh（`grep -E "a|b"` 会变成未加引号的
  括号而报语法错）。远端命令尽量写成**不含引号与括号**的形式，或把脚本落盘后
  `ssh host bash -s < script.sh`

## 5. 踩过的坑（都已验证，值得保留）

- **归档界面必须"可信点击"**：JS `.click()`、甚至手工派发完整 pointer 事件序列都无效，
  `ACCESS FILE` 保持禁用；且模块坐标会随视口变化（曾记 `1815,131`，另一轮是 `1238,131`），
  **不要凭记忆点坐标**。交接动画约需 3–5 秒
- **Pydantic 未开 `extra=forbid`**：往转发体加字段不会 422（已确认，但仍由测试兜底）
- **信任边界**：Java 管数据（`loadServerOwnedCloses` 强制覆盖客户端 `closes/history`），
  Python 管数学。迁移时**复用同一次取数**，不要另开数据来源
- **一处刻意保留的不一致**：风险指标过滤用 `> 0`（丢负价），`MarketMetrics` 用 `!= 0`
  （保负价，2020 年 4 月 WTI 收在负值）。统一它会改变已产出的数字，应作为**独立决定**
- **前端没有 vue-router**：导航必须经 `JARVIS_MODULES`，也没有 URL 参数直达标签页；
  新视图要挂在既有模块内部（研究任务视图就是这么挂在「研究助手」里的）
- **主题变量按外壳覆盖**：`--text` 在浅色外壳是近黑色。固定深底的元素必须配固定浅字，
  `color: var(--text)` 会在某个主题下读不出来
- `git`/`mvn` 写 stderr 会让 pwsh 报 `[exit code: 1]`，**要看真实输出**而不是看退出码
- 用 `Select-String` 过滤**数组**时给出的是索引而非文件行号，核对行号要小心（我栽过一次）

## 6. 建议的执行顺序

1.  三个面迁完（含三层测试）
2. 删除 Python 侧本地自算
3. 部署：预检 → 构建 → 上传 → promote → 健康检查 → 确认回退点

## 7. 前端导航体系交接（PR #15 合并后）

- `main = 6bb82cf`（merge commit，parents `217badd` + `153862f`）：Gitee 与 GitHub 均已推送，
  GitHub main 经 REST 核实为 `6bb82cf`；该提交上 CI 与 Deploy Frontend to GitHub Pages 均 success
- 导航**已换体系**，不要再按旧结构找入口：
  - 左侧固定 `global-rail`（分组图标导航，仍复用 `analysis-os/data/moduleNav.js` 的
    `buildModuleNavGroups`）+ 命令搜索（`/` 快捷键，占位文案「搜索证券、数据或命令」）
  - `entity-bar`（研究对象 + `navigateHistory` 后退/前进）与 `entity-views`
  - 已打开工作区标签条 `workspace-tab`（select / close / add），状态在
    `composables/useWorkspaceTabs.js`（sessionStorage 按用户身份分键
    `jarvis-workspace-session:`；恢复时按角色允许列表过滤，`管理` 不写入恢复集）
  - 分屏：`open-split` / `close-split`
  - **已删除**：顶部平铺标签页、二级下拉菜单（`module-index-bar` / `module-index-panel` /
    `module-group-button--admin`），测试用 `doesNotMatch(shell, /module-index-bar/)` 钉住删除
- **行情页页头以 #15 为准**（本次明确的取舍）：`217badd` 引入的 `section-kicker`、
  「行情终端」标题、`market-focus-tabs` 已在该页移除，改为「身份 + 32px 大字报价」页头。
  市场切换功能**没有丢**，改为 `QuoteStrip` 的 `@select="handleQuoteSelect"` →
  `setMarketFocus(...)`，周期与数量按钮为 `setFocusedInterval` / `setFocusedLimit`。
  `217badd` 只在该页失效，其他页面与其测试不受影响
- 全站英文信息语言页头（`CROSS MARKET / OBSERVATORY` 一类）改为中文 `<h1>`，
  测试基线同步改写为 stable product headings 那一条
- 管理员入口仍在（账户下拉菜单，按 `role === 'ADMIN'` 显示，`App.vue` 继续传 `:user`），
  文案为「旧版后台」，**不再占一级导航**；「管理员页找不到入口」不会复发
- 未被动到：`AnalysisOsPage.vue` 与 `analysis-os/motion/`（开启动画菜单），以及
  `MultiMarketBoard` / `utils/marketBoard.js`（多市场看板 + 每日要闻）
- 复核口径（作者自述不可直接引用）：PR 描述称 focused 57/57、P0 94/95 且唯一失败是缺
  `@babel/parser`——**该失败未复现且不成立**，分支与测试中不存在 babel 依赖；
  在完整 node_modules 下实测 **113/113 通过 + `vite build` 通过 8.79s**（main 为 110）
- 线上核验方法（可复现，注意编码坑）：`curl.exe -sS --ssl-no-revoke` 取
  `https://f.shengxia.me/` 的 `index.html` → 取其中 `assets/index-*.js` →
  **必须用 `Get-Content -Raw -Encoding UTF8` 读**（PS 5.1 默认按 ANSI 读会把中文弄乱，
  我第一次因此拿到全 0 的假结果）→ 计数。本次：`global-rail`=1、`entity-bar`=1、
  `entity-views`=1、`workspace-tab`=3、`command-search-row`=1、
  `搜索证券、数据或命令`=1、`旧版后台`=2、`jarvis-workspace-session:`=1，
  对照组 `module-index-bar`=0
- **仍未做视觉签字**：该 PR 无截图也无 per-PR 预览；且归档到工作空间的交接是动画驱动，
  本会话标签页内 `__jarvisArchiveDebug` 读到 `transitionState=EXTRACTING` 与
  `handoffProgress=0.000`，进不去工作空间。要看成品需在真实浏览器登录后自查

## 8. 用户定时任务（Gitee PR #11）合并记录

- `main = 1d16dc2`（merge commit，parents `06f4afb` + `ccb8cff`，来源分支
  `feature/scheduled-task-schema`，Gitee 作者 `peng-jiahhan`）
- 内容：`V9__scheduled_task.sql` / `V10__scheduled_task_run.sql` 两张表 + 实体与仓储 +
  独立线程池的调度内核（`ScheduledTaskRegistry` / `UserTaskSchedulerProvider`）+
  执行器 SPI 与 3 个执行器（`MARKET_SCAN` / `BACKTEST` / `RISK_CHECK`）+
  `ScheduledTaskController` / `ScheduledTaskService` 与 `TASK_MANAGE` 权限
- 审阅时改了一处（`ccb8cff`）：幂等键由 `LocalDateTime.now()` 改为 **cron 的计划时刻**。
  文档承诺的 `<taskId>:<计划时刻>` 只有当键真的取自计划时刻才成立，否则触发被线程池推迟
  （池只有 3 个线程，分钟级回测占满是常态）时键会漂到别的秒上，唯一约束就拦不住同一计划
  时刻的第二处触发。新增 `ScheduledTaskRegistryTest`，并做了**反向对照**（改回 `now()` 时
  该测试失败，改回计划时刻后通过）
- 分支测试：**459 全过**（0 失败 0 错误；5 个跳过＝既有 PostgreSQL 集成用例，需 `RUN_PG_IT`）
- 两处值得记住的既有设计（不是本次改的）：调度池刻意**不注册成 bean**，否则
  `TaskSchedulingAutoConfiguration` 的 `@ConditionalOnMissingBean(TaskScheduler.class)` 会让
  它不再自建，8 个静态 `@Scheduled` 静默跑到用户任务池上、把隔离做成合并；执行器只读，
  `RISK_CHECK` 不触发强平（用户任务若能触发强平就能影响他人账户）
- **教训（与 PR #15 同类）**：PR 描述只覆盖了第一个提交，仍写着「不含调度内核 / 执行器 /
  CRUD / 权限 key」，与分支实际内容矛盾（按描述审会以为它只是建表、甚至以为没有对外接口）。
  审阅一律以 `git diff main...分支` 为准，不要以描述为准；已在 Gitee 描述末尾追加审阅补充
- **部署提醒**：本次含 `V9`/`V10` 迁移，发布时须确认迁移执行路径（`migration.jar` / Flyway），
  不要只替换 `app.jar`；线上后端在本次合并前仍是旧的