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

**关键前提（已核实，勿低估）**：Java 侧只有 `MarketMetrics` 与 `RiskMetrics` 两个指标类，
**没有** quote / trend 的对应实现（`com/jarvis/research/ai/` 包已逐个文件确认；
`market/` 包只有 `QuoteDTO`，那是 DTO 不是算法）。
而 Python 侧要迁的是 `research_tools.quote_metrics`（约 80 行）与
`kline_metrics`（约 130 行，还含多周期聚合与预测区间）。

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

全迁完后才删 Python 侧的本地自算与那份跨语言契约测试。

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
- **仍未核实**：把 `dist/releases/<id>/` 上传到服务器的那条命令（`scp`/`rsync` 形式），
  以及 `promote-release.sh` 的参数列表。执行前请自己读脚本，不要依赖本文档
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