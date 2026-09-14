# JARVIS 莱茵感档案终端：Master Build Spec

版本：V1.1  
日期：2026-09-14  
状态：Master Implementation Specification / Interaction Rebuild V2 实施中  
适用分支：`feat/analysis-os-rhinelab-visual-rebuild` 及后续 Interaction Rebuild 分支  
参考工程：`LBEILC/RhineLabUI`，本地学习基线 `d9ecb6c6f7a36e8b522072a0ebbd7691a50550a7`

---

## 0. 文档目标

本文件不是单独的“屏保动画设计”，而是 JARVIS Analysis OS 主档案终端的运行时行为规范。

V1.1 起，本文件同时作为 JARVIS Analysis OS 的总构建规范。除档案海滚动、抽取、睡眠 / 唤醒外，还冻结：顶部 Module Index、模块档案内容模型、统一 Workspace Shell、11 个业务模块页面设计、Global Research Context、跨模块转场、视觉 Token、组件规范、移动端与完整验收标准。

目标：

1. 用户登录后首先进入空间化档案终端，而不是传统 Tab 页面。
2. 主档案场支持 RhineLabUI 同类的连续滚动、自由拖动、惯性滑行、吸附、循环浏览。
3. 长时间无操作时，档案海进入持续起伏、低速巡航的睡眠态，但不改变真实业务选中状态。
4. 用户一旦点击、滚轮、拖动、触摸或键盘输入，自主运动立即让出控制权。
5. 用户点击已经聚焦的模块档案时，执行“浅抬升 → 完整抽取 → 镜头靠近 → 内容揭示 → 模块页面”的连续转场。
6. 返回模块档案场时，保持刚才的模块位置，不重置主场景。

核心体验定义：

> 无操作时，系统像一个仍在运行的档案数据库；用户一碰它，它立即知道用户准备控制哪一份档案。

---

## 1. 参考工程学习结论

本规范基于对 RhineLabUI 以下实现与验证文档的实际读取，而不是仅参考截图：

- `src/archive-drag.ts`
- `src/archive-loop.ts`
- `src/scene.ts`
- `src/main.ts`
- `src/motion.ts`
- `src/viewport-layout.ts`
- `src/decryption.ts`
- `src/document-decryption.ts`
- `src/ui-transitions.ts`
- `src/appearance.ts`
- `verification/ARRAY-INPUT.md`
- `verification/LOOPING-ARCHIVE.md`
- `verification/UI-TRANSITIONS.md`
- `verification/DECRYPTION.md`
- `verification/RESPONSIVE.md`
- `DESIGN.md`
- `AGENTS.md`

### 1.1 需要迁移的是“行为模型”，不是表面样式

RhineLabUI 的主界面不是普通 carousel，也不是 CSS Tab 切页。

其核心是：

- 档案存在真实二维物理坐标：`lane + row`。
- 横向和纵向浏览都属于同一个连续档案平面。
- 拖动时使用相机投影后的两条轨道反解用户屏幕位移。
- 松手后按真实速度继续滑行。
- 速度降低后才吸附到整数档案位置。
- 档案切换时真实更新当前物理 cell。
- 聚焦只浅抬升，进入详情才进行完整抽取。
- 抽取时档案自身只做垂直运动，镜头完成靠近、转向和构图。
- 快速切换不会重置运动；旧档案继续归位，新档案从当前状态接管。
- 详情 UI 的显隐由实际相机/抽取进度驱动，而不是固定 `setTimeout`。

JARVIS 必须保留上述行为原则。

### 1.2 参考参数

参考工程中已经验证过、可用于 JARVIS 第一版调参的行为参数：

| 参数 | 参考值/行为 |
| --- | --- |
| 点击与拖动分界 | 约 7–10 CSS px |
| 松手速度采样 | 最近约 120 ms |
| 停止后无惯性阈值 | 约 80 ms |
| 自由滑行摩擦 | `exp(-2.4 × dt)` |
| 进入吸附的速度阈值 | 约 0.6 cell/s |
| 吸附临界阻尼速率 | 约 10 |
| 滚轮累计切换阈值 | 约 100 px / cell |
| 滚轮累计重置窗口 | 约 180 ms 或方向反转 |
| 聚焦预览抬升 | 约 0.4 world units |
| 完整抽取高度 | 约 4.05 world units |
| 主升降阻尼速率 | 约 4.2 |
| 归位转正衰减速率 | 约 7 |
| 详情 UI 进退场 | 约 180 ms，允许中途反向接续 |

这些值不是 JARVIS 的最终固定值，但第一实现必须以其为校准起点，而不是重新拍脑袋设计一套无关的手感。

---

## 2. JARVIS 主场景信息架构

### 2.1 主档案场不再展示股票资产卡

主档案场的卡片应代表 JARVIS 一级功能模块，而不是 AAPL / BTC / 贵州茅台等资产。

建议分类：

| Lane | 档案列 | 模块 |
| --- | --- | --- |
| 0 | MARKET / 市场 | 行情、多市场 |
| 1 | RESEARCH / 研究 | 研究助手、多空研报、财报解析 |
| 2 | INTELLIGENCE / 情报 | 产业链图谱、风险预警 |
| 3 | STRATEGY / 策略 | 回测、策略生成 |
| 4 | EXECUTION / 执行 | 模拟盘、运维 |

“研究终端”本身不是一张普通模块卡，它就是整个主档案终端。

登录主流程：

```text
LOGIN
  ↓
JARVIS ANALYSIS OS ARCHIVE
  ↓
滚动 / 拖动 / 搜索模块
  ↓
FOCUSED MODULE
  ↓
EXTRACT
  ↓
MODULE WORKSPACE
```

---

## 3. 双状态机设计

睡眠态不能和业务页面状态混成一个枚举。采用“主交互状态 + 环境状态”两个并行状态机。

### 3.1 主交互状态

```text
BROWSING
  ↓
FOCUSED
  ↓
EXTRACTING
  ↓
CAMERA_APPROACH
  ↓
REVEALING
  ↓
MODULE_ACTIVE
  ↓
MODULE_EXIT
  ↓
ALIGNING
  ↓
DESCENDING
  ↓
FOCUSED / BROWSING
```

### 3.2 环境状态

```text
AWAKE
  ↓
IDLE_ARMED
  ↓
SLEEP_ENTER
  ↓
SLEEP_DRIFT
  ↓
WAKE_CAPTURE
  ↓
WAKE_SETTLE
  ↓
AWAKE
```

### 3.3 睡眠允许范围

仅以下主状态允许进入睡眠：

- `BROWSING`
- `FOCUSED`

以下状态禁止启动睡眠：

- `EXTRACTING`
- `CAMERA_APPROACH`
- `REVEALING`
- `MODULE_ACTIVE`
- `MODULE_EXIT`
- `ALIGNING`
- `DESCENDING`

---

## 4. Idle 计时规则

默认第一版：

| 无操作时间 | 状态 | 行为 |
| --- | --- | --- |
| 0–8 s | `AWAKE` | 正常运行 |
| 8–12 s | `IDLE_ARMED` | 次要 HUD 轻微降亮 |
| 12–18 s | `SLEEP_ENTER` | 睡眠波浪和自主漂移渐入 |
| ≥18 s | `SLEEP_DRIFT` | 完整睡眠态 |

参数必须集中配置：

```ts
export const ARCHIVE_IDLE_TIMING = {
  dimStartMs: 8_000,
  motionStartMs: 12_000,
  sleepStartMs: 18_000,
}
```

禁止把多个魔法数字分散在 Vue、Three.js 和 CSS 内。

---

## 5. 什么算用户活动

任意真实交互均刷新 `lastInteractionTime`：

- `pointerdown`
- 有效 `pointermove`
- `pointerup`
- `wheel`
- `touchstart / touchmove`
- 键盘方向键
- `Enter / Esc`
- Module Index 点击
- 档案点击
- 搜索框输入
- 返回档案按钮

桌面鼠标仅发生 1–2 px 抖动时不应刷新 Idle。

建议有效鼠标运动阈值：

```text
3–5 CSS px
```

---

## 6. 睡眠渐入：SLEEP_ENTER

不能在第 18 秒突然开启所有运动。

定义连续参数：

```ts
sleepAmount: number // 0..1
```

从 12–18 秒平滑增长：

```text
sleepAmount = smoothstep(12s, 18s)
```

它共同驱动：

- 档案海睡眠波浪权重
- 自主巡航权重
- 睡眠相机偏移
- HUD 降亮
- 环境扫描/呼吸效果

必须保证所有效果连续可逆。

---

## 7. 睡眠档案海的运动

### 7.1 基础合成

档案最终高度应由多个独立运动层叠加：

```text
worldY =
  baseY
  interactionWave
  normalIdleWave
  sleepWave
  hoverLift
  selectedLift
  extractionLift
```

优先级：

```text
extraction / selected lift
>
user drag / wake settle
>
sleep motion
>
normal idle breathing
```

### 7.2 睡眠波浪

不是随机抖动，而是低频连续表面。

第一版建议：

```ts
sleepWave =
  Math.sin(time * 0.55 + row * 0.22 - lane * 0.35) * 0.11
  + Math.sin(time * 0.29 - row * 0.11 + lane * 0.27) * 0.05
```

并乘：

```text
sleepAmount
```

最大位移建议：

```text
卡片高度的 5%–7%
```

禁止做成明显的海浪游戏或高频弹跳。

---

## 8. 睡眠滚动：视觉运动与逻辑选择必须分离

这是实现中最重要的约束之一。

睡眠态允许档案海自行缓慢滚动，但不能自动改变业务当前模块。

必须保留两个概念：

```ts
logicalFocus     // 真实当前模块
renderedPosition // 当前画面视觉位置
```

新增：

```ts
sleepOffsetLane
sleepOffsetRow
```

最终显示轨道：

```text
renderedLane = userLaneTrack + sleepOffsetLane
renderedRow  = userRowTrack  + sleepOffsetRow
```

睡眠十分钟后，真实当前模块仍必须是进入睡眠前的模块。

---

## 9. 睡眠自主巡航

建议只围绕当前逻辑档案附近进行闭合低频运动，不允许无限向一个方向移动。

第一版范围：

```text
lane: ±0.25 ～ 0.50 cell
row:  ±1.0  ～ 2.5 cell
```

建议路径：

```ts
sleepOffsetLane = Math.sin(time / 11) * 0.35

sleepOffsetRow =
  Math.sin(time / 7.3) * 1.4
  + Math.sin(time / 17) * 0.55
```

周期应长于用户常见操作节奏，建议 20–40 秒级。

---

## 10. 睡眠相机

可以轻微呼吸，但禁止主动“找卡片”。

允许：

- X 方向轻微偏移
- Y 方向更小偏移
- FOV 极轻微变化

推荐上限：

| 参数 | 最大建议 |
| --- | --- |
| Camera X | 画面宽度的约 0.5%–1.5% |
| Camera Y | 约 0.3%–0.8% |
| FOV | ±0.2°～0.5° |

禁止：

- 自动进入详情
- 自动抽取模块
- 大幅环绕
- 大幅 zoom
- 自动改变 `logicalFocus`

---

## 11. 睡眠 HUD

睡眠不是隐藏所有 UI。

### 11.1 始终显示

- JARVIS 品牌
- 当前逻辑模块
- 系统状态
- Powered by JARVIS

### 11.2 渐弱显示

- 操作说明
- 辅助导航
- 更新时间
- 次要编号
- 搜索提示

建议 opacity：

```text
1.0 → 0.35~0.45
```

### 11.3 状态文案

建议克制使用：

```text
IDLE / ARCHIVE DRIFT
```

不使用普通应用风格的“正在休眠……”。

---

## 12. 唤醒总规则

任何有效用户输入：

```text
用户输入优先级 > 所有自动动画
```

统一入口：

```ts
interruptSleep(reason, target?)
```

建议职责：

```ts
function interruptSleep(reason, target) {
  environmentState = 'WAKE_CAPTURE'
  stopAutoCruise()
  clearIdleTimers()
  preserveCurrentRenderedPosition()
  calculateWakeTarget(reason, target)
  beginSleepAmountDecay()
  restoreHud()
  dispatchInputToArchiveController()
}
```

不能让点击、滚轮、拖动先等睡眠动画播完。

---

## 13. Wake Target Resolution

用户提出“档案海会迅速回退到当前点击或者滑动到的页面”，正式定义为 Wake Target Resolution。

目标来源优先级：

```text
clicked physical cell
>
active drag position
>
wheel destination
>
previous logical focus
```

对应：

```ts
click  -> wakeTarget = clickedCell
drag   -> wakeTarget = nearestCell(currentDragPosition)
wheel  -> wakeTarget = nearestCell(currentScrollPosition)
key    -> wakeTarget = requestedNavigationCell
```

---

## 14. 点击唤醒

睡眠中点击任意可见档案：

```text
SLEEP_DRIFT
  ↓
pointerdown
  ↓
立即冻结自主巡航
  ↓
记录点击 cell
  ↓
WAKE_CAPTURE
  ↓
快速收束到点击档案
  ↓
FOCUSED
```

收束时长建议：

```text
180–320 ms
```

第一版目标：约 240 ms。

规则：

- 点击非当前档案：只唤醒并聚焦。
- 点击已经聚焦的档案：继续执行完整抽取。
- 双击可允许直接 `wake → focus → extract`。

---

## 15. 拖动唤醒

拖动必须拥有最高交互优先级。

`pointerdown` 时：

1. 立即冻结当前睡眠视觉位移。
2. 将当前 rendered position 反解回可接管的 archive track。
3. 当前自动速度归零。
4. 用户手势从当前画面直接接管。

目标感觉：

> 用户按住档案海的瞬间，像直接把正在漂动的档案场抓住。

禁止：

```text
先回中心 → 等待 300ms → 再开始拖动
```

---

## 16. 滚轮唤醒

滚轮触发后：

1. 中止睡眠巡航。
2. 保留当前可见 row。
3. 将当前视觉位置吸收到用户轨道。
4. 立即叠加滚轮 delta。
5. 进入正常 wheel accumulation / inertia / snap。

禁止：

```text
睡眠当前位置
→ 强制退回旧 logicalFocus
→ 再滚一格
```

否则会产生明显“倒车”。

---

## 17. WAKE_SETTLE

睡眠参数不能瞬间清零。

需要连续收束：

```text
sleepAmount → 0
sleepOffset → userTrack
sleepCameraOffset → 0
HUD opacity → active value
```

推荐临界阻尼：

```text
rate = 9–13
```

视觉时长目标：

```text
180–300 ms
```

比普通档案轨道稍快，但不能出现 teleport。

---

## 18. 唤醒和抽取必须允许重叠

如果用户动作已经明确表达“我要进入该模块”，不应等 WAKE_SETTLE 完整结束再开始抽取。

建议时间关系：

```text
0–180ms    sleepAmount 快速回落
80–260ms   focus convergence
160ms 起   extraction 开始
```

形成一条连续动作，而不是：

```text
醒来 → 停顿 → 聚焦 → 停顿 → 抽卡
```

---

## 19. 模块档案抽取规范

### 19.1 Preview / Focused

仅聚焦：

```text
lift ≈ 0.4
```

相机仍保持档案海浏览构图。

### 19.2 Enter Module

点击当前聚焦模块：

```text
0.4 → 4.05
```

完整抽取必须遵守：

- 档案只改变垂直 Y。
- 不允许为构图让卡片朝用户方向飞。
- 不允许横向插值到屏幕左侧。
- 卡片获得高度，镜头负责靠近和转向。

### 19.3 Camera Approach

当 lift 超过一段安全高度后才开始镜头转向。

目标：

- 左侧或主视觉区保留被抽出的模块档案。
- 右侧逐渐留出业务工作区。
- 背景档案海保持可见，不应整个消失。

---

## 20. 页面内容揭示

JARVIS 不直接复制 RhineLabUI 的品牌扫描表现，但采用相同的“实体状态驱动正文”的原则。

建议阶段：

```text
ACCESSING MODULE
  ↓
DATA CHANNEL READY
  ↓
RESEARCH CONTEXT READY
  ↓
MODULE UI REVEAL
```

页面正文 opacity / translate 由实际 camera progress 或 extraction progress 驱动。

禁止单纯：

```ts
setTimeout(() => showPage(), 700)
```

推荐：

```ts
detailVisibility = f(extractionProgress, cameraProgress)
```

这样快速反向退出时可以自然从当前值接续。

---

## 21. 返回模块档案场

模块页返回：

```text
MODULE_ACTIVE
  ↓
MODULE_EXIT
  ↓
CARD_ALIGN
  ↓
DESCENDING
  ↓
FOCUSED
```

规则：

1. 页面内容先淡出，约 180ms。
2. 抽出的档案保持原高度。
3. 如有旋转/检查状态，先在高处转正。
4. 转正完成后再下降。
5. 镜头退回档案海浏览构图。
6. 返回后仍然聚焦刚才模块。
7. 重置 Idle Timer，不能刚返回就直接睡眠。

---

## 22. 快速切换与中断

必须保留当前运动状态。

例如 A 卡正在下降时用户再次选择 A：

- 不允许把 A 重置到 `lift=0`。
- 应从它当前下降高度重新成为 active card。

选择 B 时：

- A 作为 returning/outgoing visual 继续归位。
- B 从自己的当前状态开始聚焦。

这和 RhineLabUI 的 outgoing group 思路一致。

---

## 23. Module Index / 顶部导航联动

顶部导航不是传统 Tab，而是档案索引。

### 滚动档案海

档案 physical focus 变化：

```text
Three.js selection
→ Module Index 自动高亮/滚动到当前模块
```

### 点击导航项

点击非当前项：

```text
Module Index click
→ 档案海移动并聚焦对应卡片
```

点击当前已聚焦项：

```text
→ 执行完整抽取进入模块
```

睡眠中点击 Module Index：

```text
SLEEP → WAKE → MOVE TO MODULE → FOCUSED
```

---

## 24. 页面与睡眠的边界

进入任何业务模块后暂停 Sleep Timer：

- 行情
- 多市场
- 回测
- 模拟盘
- 研究助手
- 多空研报
- 财报解析
- 产业链图谱
- 风险预警
- 策略生成
- 运维

返回 Archive 后：

```text
lastInteractionTime = now
environmentState = AWAKE
```

---

## 25. 浏览器后台行为

当：

```ts
document.hidden === true
```

要求：

- 暂停 Sleep Timer。
- 停止自主巡航积分。
- 停止或显著降低 Three.js RAF。
- 不累积睡眠时间。

恢复标签页后：

```text
environmentState = AWAKE
lastInteractionTime = now
```

禁止用户切走半小时回来后画面已经自主漂移很远。

---

## 26. Reduced Motion

当系统或 JARVIS 设置要求减少动态效果时：

保留：

- HUD 降亮
- 极弱的静态呼吸
- Idle 状态提示

禁用：

- 持续自动滚动
- 睡眠镜头巡航
- 长距离 Wake 收束
- 复杂波浪传播

唤醒目标：

```text
≤100 ms 内恢复可操作状态
```

---

## 27. 手机与触摸

移动端没有 hover。

`touchstart` 立即执行：

```text
SLEEP → WAKE_CAPTURE
```

随后：

- tap：聚焦档案。
- drag：直接接管档案平面。
- 多指：取消档案拖动，交给必要的页面手势或忽略。

禁止等待睡眠动画结束再响应触摸。

---

## 28. 性能约束

Sleep Mode 不允许比正常浏览显著更重。

禁止为了睡眠：

- 创建新的 WebGL context。
- 每帧创建 geometry/material/texture。
- 每帧重绘 CanvasTexture。
- 新增整套 post-processing 管线。
- 将卡片数量翻倍只为睡眠动画。

目标：

```text
Sleep GPU Cost <= Normal Browsing GPU Cost
```

优先使用：

- 已有实例矩阵/组 transform。
- uniform。
- 现有 RAF。
- 连续数学函数。

---

## 29. 推荐代码结构

不要把所有状态塞进当前 `AnalysisOsPage.vue`。

建议后续 Interaction Rebuild 拆分：

```text
frontend/src/components/analysis/
  ModuleArchiveScene.vue
  ModuleArchiveHud.vue

frontend/src/composables/analysis/
  useArchiveNavigation.js
  useArchiveTransition.js
  useArchiveIdle.js

frontend/src/lib/analysis/
  archiveMomentum.js
  archiveLoop.js
  archiveMotion.js
  archiveIdleController.js
```

建议职责：

### `archiveMomentum`

- 拖动速度采样
- coasting
- snapping
- 双轨同步衰减

### `archiveLoop`

- lane/row 无限逻辑位置
- 模块到 cell 映射
- 最邻近循环 occurrence
- 每 lane 选择记忆

### `archiveIdleController`

- Idle Timer
- `sleepAmount`
- sleep offsets
- Wake Target Resolution
- `interruptSleep()`

### `archiveMotion`

- focused lift
- extraction lift
- return align
- sleep wave
- camera transition curves

### `ModuleArchiveScene`

- 只消费状态并进行 Three.js 绘制
- 不自行决定 18 秒是否进入睡眠

### `AnalysisOsPage / Hub`

- 管理业务模块映射
- 管理进入/退出模块
- 管理 HUD、索引和页面容器

---

## 30. 推荐核心数据结构

```ts
type ArchiveInteractionState =
  | 'BROWSING'
  | 'FOCUSED'
  | 'EXTRACTING'
  | 'CAMERA_APPROACH'
  | 'REVEALING'
  | 'MODULE_ACTIVE'
  | 'MODULE_EXIT'
  | 'ALIGNING'
  | 'DESCENDING'

type ArchiveEnvironmentState =
  | 'AWAKE'
  | 'IDLE_ARMED'
  | 'SLEEP_ENTER'
  | 'SLEEP_DRIFT'
  | 'WAKE_CAPTURE'
  | 'WAKE_SETTLE'

type ArchiveCell = {
  lane: number
  row: number
}

type ArchiveRuntimeState = {
  interactionState: ArchiveInteractionState
  environmentState: ArchiveEnvironmentState
  logicalFocus: string
  selectedCell: ArchiveCell
  renderedLane: number
  renderedRow: number
  sleepAmount: number
  sleepOffsetLane: number
  sleepOffsetRow: number
  wakeTarget: ArchiveCell | null
  laneVelocity: number
  rowVelocity: number
  extraction: number
  detailVisibility: number
}
```

---

## 31. 调试接口

开发模式建议暴露：

```js
window.__jarvisArchiveDebug
```

至少包含：

```ts
{
  interactionState,
  environmentState,
  idleSeconds,
  sleepAmount,
  sleepOffsetLane,
  sleepOffsetRow,
  logicalFocus,
  selectedCell,
  renderedPosition,
  wakeTarget,
  laneVelocity,
  rowVelocity,
  extraction,
  detailVisibility,
  canvasCount,
}
```

用于 Playwright / DevSpace 验收。

---

## 32. 自动化验收清单

### A. 睡眠进入

- [ ] 8s 后辅助 HUD 开始弱化。
- [ ] 12s 后档案波浪渐入。
- [ ] 18s 后进入完整 Sleep Drift。
- [ ] 睡眠过程中 `logicalFocus` 不变化。
- [ ] 睡眠巡航不无限漂离逻辑锚点。

### B. 点击唤醒

- [ ] 点击任意可见卡立即终止自动巡航。
- [ ] 目标为实际点击 physical cell。
- [ ] 约 180–320ms 内收束到 Focused。
- [ ] 点击已聚焦卡可继续抽取。

### C. 滚轮唤醒

- [ ] Sleep 中滚轮不会先倒车到旧 logicalFocus。
- [ ] 可从当前画面继续滚动。
- [ ] 仍保留 wheel accumulation、惯性与吸附。

### D. 拖动唤醒

- [ ] pointerdown 立即接管自主运动。
- [ ] 当前视觉位置不会跳变。
- [ ] 快拖和慢拖产生不同惯性距离。
- [ ] 中途反向能重新估计速度。
- [ ] 再次按下正在滑行的阵列可直接接住。

### E. 抽取

- [ ] Focused 仅浅抬升。
- [ ] 进入页面时升到完整抽取高度。
- [ ] 抽取只移动 Y。
- [ ] 镜头完成左右构图和靠近。
- [ ] 背景档案仍保持可见。
- [ ] 页面 UI 根据实际进度显现。

### F. 返回

- [ ] 页面先退出。
- [ ] 卡片先高处转正。
- [ ] 对齐后才下降。
- [ ] 返回后保持原模块。
- [ ] 重新开始 Idle Timer。

### G. 环境与生命周期

- [ ] `document.hidden` 时不累积睡眠时间。
- [ ] 返回页面后重新从 Awake 开始。
- [ ] Reduced Motion 不持续自主滚动。
- [ ] 多次 Sleep/Wake 不新增 RAF。
- [ ] 多次 Sleep/Wake 不新增 WebGL context。
- [ ] 1440 / 1280 / 1024 / 430 / 390 无横向布局溢出。

---

## 33. 人工视觉验收标准

Sleep 正确感觉：

> 系统暂时没人操作，但档案数据库仍在运行。

不能像：

> 网站开始播放屏保。

Wake 正确感觉：

> 用户一碰系统，档案海立即被抓住并服从用户控制。

不能像：

> 用户先等系统播完返回动画，然后系统才响应。

抽取正确感觉：

> 当前模块从真实档案海中被物理抽出，随后工作区从这份档案展开。

不能像：

> 点了一张 3D 卡，然后普通 Vue 页面突然出现。

---

## 34. 实施顺序

### Phase 1 — Navigation Core

1. 模块数据替换当前市场资产主卡。
2. 建立 lane / row module mapping。
3. 实现连续滚轮切档。
4. 实现自由二维拖动。
5. 实现 coasting + snapping。
6. 实现循环坐标和每 lane 选择记忆。

完成条件：主场景已经像 RhineLabUI 一样“可滚、可拖、可滑”。

### Phase 2 — Focus & Extraction

1. 浅聚焦抬升。
2. 完整抽取。
3. Camera Approach。
4. Module HUD reveal。
5. 返回时 align → descend。
6. 快速中断状态接续。

完成条件：点击某个模块不是直接切页，而是完成物理档案抽取。

### Phase 3 — Sleep / Wake

1. Idle Timer。
2. HUD dim。
3. Sleep Wave。
4. Sleep Drift。
5. Sleep Camera。
6. `interruptSleep()`。
7. click/wheel/drag/key wake target。
8. Reduced Motion。

完成条件：长时间不操作时档案海自主运行，任意输入能立即接管。

### Phase 4 — Module Workspace UI

优先统一三个代表页面：

1. 行情
2. 模拟盘
3. 财报解析

然后扩展到其他模块。

统一要求：

- `RETURN TO ARCHIVE`
- 浅色档案工作台语言
- 同一 HUD / 细线 / 标题层级
- 页面进出由 Archive Transition 驱动

---

## 35. 非目标与限制

本阶段不应：

- 复制 Rhine Lab / 明日方舟品牌、Logo、剧情和名称到 JARVIS。
- 直接把 RhineLabUI 的 GLB、美术、音频资源打包进 JARVIS。
- 将原项目业务数据替换为虚构数据。
- 修改 JARVIS 认证、CSRF、订单、市场 API 协议来配合视觉效果。
- 为了像参考项目而牺牲业务页可读性。

可借鉴/迁移的是：

- MIT 代码层面的运动算法思想与实现结构。
- 状态机。
- 输入与惯性模型。
- 镜头/抽取行为原则。
- 视觉层级与交互节奏。

JARVIS 最终品牌必须保持原创的 `JARVIS Analysis OS`。

---

## 36. 最终目标流程

```text
用户登录
  ↓
进入 JARVIS Analysis OS 档案海
  ↓
自由滚动 / 拖动 / 惯性浏览模块
  ↓
长时间无操作
  ↓
HUD 降亮
  ↓
档案海起伏 + 低速睡眠巡航
  ↓
用户 click / wheel / drag / key
  ↓
立即中断自主运动
  ↓
档案海从当前画面被用户接管
  ↓
快速收束到用户目标模块
  ↓
模块卡 Focused
  ↓
点击 / Enter
  ↓
档案垂直抽取
  ↓
镜头靠近并重新构图
  ↓
访问/解密式内容揭示
  ↓
进入模块 Workspace
  ↓
RETURN TO ARCHIVE
  ↓
页面退出
  ↓
卡片高处转正
  ↓
下降归位
  ↓
回到之前的模块位置
  ↓
重新开始 Idle Timer
```

---

## 37. 实施完成定义（Definition of Done）

本规范不能因为“主画面看起来像 RhineLabUI”就判定完成。

只有同时满足以下条件才算完成：

1. 主档案海真实支持连续滚动与自由拖动。
2. 快拖/慢拖存在不同惯性距离。
3. 睡眠态发生在同一个 Three.js 主场景中。
4. 睡眠态会起伏和低速巡航。
5. 睡眠不改变真实业务选择。
6. 用户输入零等待接管自主运动。
7. 点击/滑动后能正确收束到实际用户目标。
8. Focused 与 Extracting 是两个不同状态。
9. 档案完整抽取只改变高度，镜头完成构图。
10. 页面正文随实际场景进度揭示。
11. 返回遵循“退出 → 转正 → 下降”。
12. 快速切换和反向操作从当前运动状态接续。
13. 生命周期不存在 RAF / WebGL context 泄漏。
14. Reduced Motion、桌面和移动端均有正确降级行为。
15. JARVIS 现有业务 API、认证、模拟交易逻辑保持不变。

达到以上条件后，才能把 `JARVIS Analysis OS Interaction Rebuild V2` 标记为完成。

---

# Part II — 全站终端 UI 与业务 Workspace 补全规范

## 38. 补全范围

本 Part 用于补齐早期 JARVIS 视觉重构与交易终端会话中已经提出、但此前没有完整进入睡眠 / 唤醒文档的页面级设计要求。

V1.1 起，以下内容与 Part I 具有同等约束力：

1. 登录后进入 Archive Hub，而不是传统 Tab 首页。
2. 顶部导航升级为可滚动 `Module Index`，与 Three.js 档案场双向同步。
3. 模块卡片拥有统一的数据模型、编号、分类与聚焦 HUD。
4. 所有业务页使用统一的 `Archive Workspace Shell`。
5. 11 个业务模块逐页固定信息架构与视觉层级。
6. Selected Asset / Global Research Context 在模块之间继承。
7. 模块之间直接跳转与返回 Archive 使用两套不同转场。
8. 全站统一视觉 Token、组件规范和禁止模式。
9. 桌面、窄桌面、平板、手机使用明确的重排规则。

本 Part 不改变 Part I 已冻结的滚动、惯性、抽取、睡眠、唤醒、返回归位行为。

---

## 39. 最终产品信息架构

登录后的产品结构冻结为：

```text
PUBLIC LANDING
      ↓
     LOGIN
      ↓
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      JARVIS ANALYSIS OS
         ARCHIVE HUB
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
      ↓
滚轮 / 拖动 / 惯性 / Module Index
      ↓
MODULE FOCUSED
      ↓
档案抽取 + Camera Approach
      ↓
MODULE WORKSPACE
      │
      ├── Cross Module Navigation
      │
      └── RETURN TO ARCHIVE
               ↓
        高处转正 / 下降归位
               ↓
          ARCHIVE HUB
               ↓
        Idle / Sleep Drift
```

### 39.1 “研究终端”不是一个普通 Tab

`研究终端` 代表整个 Archive Hub 本身。

禁止继续把它与 `行情 / 多市场 / 回测 / 模拟盘 ...` 平级显示为传统网页 Tab。

### 39.2 业务模块集合

第一版固定 11 个一级模块：

| No. | Key | 中文名 | 分组 |
| --- | --- | --- | --- |
| 01 | `market` | 行情 | MARKET |
| 02 | `cross-market` | 多市场 | MARKET |
| 03 | `backtest` | 回测 | STRATEGY |
| 04 | `sim-trade` | 模拟盘 | EXECUTION |
| 05 | `ai-research` | 研究助手 | RESEARCH |
| 06 | `bull-bear` | 多空研报 | RESEARCH |
| 07 | `financial` | 财报解析 | RESEARCH |
| 08 | `industry-chain` | 产业链图谱 | INTELLIGENCE |
| 09 | `risk` | 风险预警 | INTELLIGENCE |
| 10 | `strategy` | 策略生成 | STRATEGY |
| 11 | `ops` | 运维 | EXECUTION / SYSTEM |

模块数量可以后续扩展，但编号一旦进入用户版本，应尽量保持稳定。

---

## 40. 顶部 Module Index

### 40.1 定位

顶部栏不是传统 Tab Bar，而是：

```text
MODULE INDEX / 系统模块索引
```

它是 Three.js 档案场的另一种控制方式，而不是独立导航逻辑。

### 40.2 桌面结构

建议结构：

```text
JARVIS / ANALYSIS OS

01 MARKET · 02 CROSS MARKET · 03 BACKTEST · 04 SIM TRADE · ... · 11 OPS

                                     SEARCH   LIVE API   14:32:08
```

实际实现中不要一次强行压缩显示 11 项。索引区域必须支持横向滚动 / 自动居中。

### 40.3 当前模块位置

当前模块高亮应包含：

- 模块编号。
- 英文短名。
- 中文名可作为次级文字或 hover / active 展开项。
- 1px 细线或底部轨迹标记。
- 字重 / 字距轻微变化。
- 不使用大面积彩色胶囊按钮。

### 40.4 自动跟随

当 Three.js physical focus 改变：

```text
Archive physical focus
→ resolve module key
→ Module Index active key
→ scroll active index into preferred viewport zone
```

优先把当前项保持在导航中部或中右侧，不要求绝对居中。

### 40.5 点击规则

点击非当前模块：

```text
Module Index click
→ interruptSleep()
→ archive track scroll / snap to target module
→ FOCUSED
```

点击已经 FOCUSED 的当前模块：

```text
FOCUSED
→ EXTRACTING
→ MODULE WORKSPACE
```

### 40.6 键盘

- `← / →`：切模块列或 Module Index 邻项。
- `↑ / ↓`：同列浏览。
- `Enter`：进入当前模块。
- `Esc`：返回 Archive 或退出当前表层。
- `/`：聚焦 Module / Global Search。

---

## 41. 模块档案内容模型

Three.js 模块档案不能再直接使用股票资产名称作为主卡内容。

每个模块采用统一元数据：

```ts
type JarvisModuleDescriptor = {
  id: string
  no: number
  lane: number
  row: number
  key: string
  labelEn: string
  labelZh: string
  category: string
  summary: string
  capabilities: string[]
  routeKey: string
  availability: 'ready' | 'degraded' | 'offline'
}
```

### 41.1 3D 实体正面

实体上只保留非常克制的信息：

```text
07
FINANCIAL
FR-07
```

禁止把说明、按钮、实时价格等全部绘制到 Three.js 档案前脸。

### 41.2 Focus HUD

聚焦后由 DOM HUD 展示：

```text
MODULE / 07
FINANCIAL REPORT
财报解析

阅读公司财务结构、盈利质量、现金流与异常变化。

FUNDAMENTALS · FILING · QUALITY

ACCESS MODULE →
```

### 41.3 抽取后

抽取进入阶段可以额外显示：

- 服务可用性。
- 当前 Global Research Context。
- 数据时间。
- 最近一次访问。
- 进入 Workspace 的系统状态。

这些信息属于 transition HUD，不应全部常驻主场景。

---

## 42. Archive Workspace Shell

所有业务模块必须复用统一页面骨架。

### 42.1 桌面通用骨架

```text
┌────────────────────────────────────────────────────────────┐
│ MODULE 07 / FINANCIAL       CONTEXT: NVDA      LIVE 14:32 │
│ 财报解析                                  RETURN TO ARCHIVE │
├───────────────┬────────────────────────────────────────────┤
│               │                                            │
│ CONTEXT       │              PRIMARY WORK AREA             │
│ / INDEX       │                                            │
│               │                                            │
├───────────────┴────────────────────────────────────────────┤
│ SECONDARY DATA / LOG / RESULT / EVIDENCE                  │
└────────────────────────────────────────────────────────────┘
```

### 42.2 Shell 固定元素

每个 Workspace 至少拥有：

- Module No.
- 英文模块名。
- 中文模块名。
- Global Research Context。
- 数据状态与更新时间。
- `RETURN TO ARCHIVE`。
- 页面级工具区。
- 主工作区。
- 次级证据 / 日志 / 结果区。

### 42.3 不要求每页三栏

Shell 统一的是视觉、状态和导航，而不是要求每个页面机械地三栏等宽。

图表页、图谱页、聊天研究页、交易页可拥有不同内容比例。

---

## 43. Global Research Context

### 43.1 目标

用户在一个模块中选择研究对象后，进入其他相关模块时应默认保留该对象。

例如：

```text
Market 选择 NVDA
→ Financial 默认 NVDA
→ Risk 默认 NVDA
→ Industry Chain 定位 NVIDIA 节点
→ AI Research 自动带入 NVDA context
→ Sim Trade 默认加载 NVDA
```

### 43.2 数据结构

建议：

```ts
type GlobalResearchContext = {
  market?: string
  symbol?: string
  name?: string
  entityId?: string
  sourceModule?: string
  updatedAt?: number
}
```

### 43.3 继承原则

- 页面进入时优先使用兼容的 Global Context。
- 用户在当前模块显式换标的时更新 Context。
- 不支持该 Context 的页面不应清空它。
- 返回 Archive 时 Context 保留。
- 退出登录时清理会话级 Context。

### 43.4 与服务器数据边界

Global Research Context 是前端研究工作流上下文，不得绕过现有后端权限和 API 查询边界。

---

## 44. 行情 / Market Workspace

### 44.1 定位

行情页是专业市场观察工作区，不做“卡片 Dashboard”。

### 44.2 桌面布局

```text
┌─────────────────────────────────────────────────────────┐
│ MARKET / NVDA   218.29  -0.03%      LIVE / 14:32:08    │
├─────────┬──────────────────────────────┬────────────────┤
│WATCHLIST│                              │QUOTE / PROFILE │
│         │          K LINE              │                │
│         │          VOLUME              │MARKET STATE    │
│         │          INDICATORS          │TECH SUMMARY    │
├─────────┴──────────────────────────────┴────────────────┤
│ TIME & SALES / DEPTH / NEWS / INDICATOR DETAIL         │
└─────────────────────────────────────────────────────────┘
```

### 44.3 优先级

1. 图表是主画布。
2. Watchlist 是索引，不是视觉中心。
3. Quote / Profile 是当前标的档案。
4. 新闻和技术指标是次级区。

### 44.4 进入动效

从 Market 档案抽取后：

- 抽出档案可短暂保留为左侧 / 背景 module marker。
- K 线主画布成为视觉中心。
- 不允许突然切到完全不同的深黑传统后台页面。

---

## 45. 多市场 / Cross Market Workspace

### 45.1 定位

跨市场状态对比，而非市场类型按钮列表。

### 45.2 主要区域

- A Share。
- US Stock。
- Crypto。
- Gold / Commodity。

建议中央展示：

- 市场强弱。
- 方向。
- 波动率。
- 相关性。
- 主要代表资产。
- 资金 / 风险状态。

### 45.3 交互

点击某个资产：

- 更新 Global Research Context。
- 页面右侧 / 下方更新选中 Context。
- 可继续进入 Market / Risk / AI Research。

---

## 46. 回测 / Backtest Laboratory

### 46.1 定位

视觉语义为“策略实验档案”，而不是普通表单页。

### 46.2 布局

左侧：`EXPERIMENT PARAMETERS`

- Instrument。
- Period。
- Initial Cash。
- Strategy Parameters。
- Fees / Slippage。

中央：

- Equity Curve。
- Drawdown。

右侧：

- CAGR。
- Sharpe。
- Max Drawdown。
- Win Rate。
- Trades。

底部：

- Trade Log。
- Monthly Return。
- Drawdown Periods。

### 46.3 文案语义

启动操作：

```text
RUN EXPERIMENT
```

运行中：

```text
PROCESSING / 037%
```

完成：

```text
EXPERIMENT COMPLETE
```

保留真实业务状态，不伪造进度。

---

## 47. 模拟盘 / Execution Workspace

### 47.1 原则

模拟盘保持专业交易终端逻辑，使用：

```text
Rhine/JARVIS Shell + Professional Trading Core
```

不能为了统一浅色终端视觉而牺牲交易可读性。

### 47.2 主布局

- 顶部：Symbol / Quote / Account / PnL。
- 中央最大区域：K 线 / 图表 / 深度。
- 下部：Positions / Orders / Stops / History。
- Order Ticket：浮动面板或右侧抽屉，不常驻抢占主图表。

### 47.3 语义色

- Buy / Positive 可以使用受控绿色。
- Sell / Negative 可以使用受控红色。
- 这些属于金融语义色，不受“全站只用琥珀色”限制。

### 47.4 既有交易能力

现有模拟交易、服务端 Stop、持仓、订单和图表逻辑不得因为视觉重构被弱化或移除。

---

## 48. 研究助手 / Research Intelligence Workspace

### 48.1 禁止普通 Chat UI

不要以左右聊天气泡为主要视觉结构。

### 48.2 三层结构

左侧：`RESEARCH TASK`

- 当前 Context。
- 问题。
- 历史任务。

中央：`ANALYSIS`

AI 输出按研究笔记结构展示：

```text
01 THESIS
02 EVIDENCE
03 COUNTER EVIDENCE
04 RISK
05 CONCLUSION
```

右侧：`EVIDENCE`

- 数据引用。
- 财务指标。
- 新闻 / 资料来源。
- 相关模块快捷入口。

### 48.3 引用

证据必须和分析正文建立可追踪关系，不只把来源堆在页面底部。

---

## 49. 多空研报 / Bull–Bear Dossier

### 49.1 布局

桌面采用对称争议结构：

```text
┌──────────────────────┬──────────────────────┐
│      BULL CASE       │      BEAR CASE       │
│ thesis / evidence    │ thesis / evidence    │
│ invalidation         │ invalidation         │
├──────────────────────┴──────────────────────┤
│              DISPUTE CORE                  │
├─────────────────────────────────────────────┤
│                 SYNTHESIS                   │
└─────────────────────────────────────────────┘
```

### 49.2 内容

两侧都至少包含：

- 核心论点。
- 支撑证据。
- 反证。
- 置信度。
- 失效条件。

最终 `SYNTHESIS` 不能简单判定多或空，而应展示争议核心和需继续观察的条件。

---

## 50. 财报解析 / Financial Filing Workspace

### 50.1 页面语义

这一页应是全站最接近“档案展开”的业务页。

页首：

```text
COMPANY FILE
NVDA / NVIDIA CORP.
FY2026 Q3
```

### 50.2 第一层指标

- Revenue。
- Net Income。
- Gross Margin。
- FCF。
- Debt。
- EPS。

使用连续指标条 / 表格，不强制做六张大圆角卡。

### 50.3 三表

```text
01 INCOME
02 BALANCE
03 CASH FLOW
```

页签使用统一细线和移动 indicator。

### 50.4 分析区

`QUALITY ANALYSIS`

- 盈利质量。
- 现金流匹配。
- 非经常性项目。
- 异常变化。

`RISK FLAGS`

- 明确风险。
- 触发依据。
- 数据期。

---

## 51. 产业链图谱 / Industry Graph Workspace

### 51.1 主画布

中央约 65–75% 区域优先给真正的产业链图谱。

### 51.2 左侧

层级索引：

- Upstream。
- Midstream。
- Downstream。
- Category Filter。

### 51.3 右侧 Node File

```text
NODE / NVDA
NVIDIA
SEMICONDUCTOR
POSITION: CORE
RELATED: ...
```

### 51.4 跨模块动作

节点详情可进入：

- `OPEN MARKET`
- `OPEN FINANCIAL`
- `OPEN RISK`
- `OPEN RESEARCH`

并更新 Global Research Context。

---

## 52. 风险预警 / Risk Surveillance Workspace

### 52.1 定位

页面语义是“风险监控与事件档案”，不是红色卡片墙。

### 52.2 布局

左侧：风险事件时间线。

中央：当前 Risk Object。

右侧：

- Severity。
- Trigger。
- Source。
- Affected Area。
- Invalidation / Resolution Condition。
- Confirmation State。

### 52.3 色彩

整页仍保持浅色档案系统，只让真正的 HIGH / CRITICAL 风险使用受控红色。

---

## 53. 策略生成 / Strategy Foundry

### 53.1 工作流

```text
OBJECTIVE
  ↓
GENERATED STRATEGY
  ↓
VALIDATION
  ↓
SEND TO BACKTEST
```

### 53.2 Objective

- Instrument。
- Horizon。
- Risk Budget。
- Target。
- Constraints。

### 53.3 Generated Strategy

必须以结构化规则展示：

- Entry。
- Exit。
- Position Sizing。
- Risk Control。
- Data Requirement。

不能只生成一段自然语言。

### 53.4 Validation

至少检查：

- Look-ahead risk。
- Data availability。
- Parameter range。
- Risk constraints。

### 53.5 Backtest 联动

`SEND TO BACKTEST` 应把合法策略参数带入 Backtest Workspace，而不是让用户重新输入。

---

## 54. 运维 / System Operations Workspace

### 54.1 定位

运维是 JARVIS 系统诊断档案。

### 54.2 顶层状态

`SYSTEM STATUS`

至少包括：

- Java API。
- Python AI。
- Database。
- Market Feed。
- SSE。
- Auth。

### 54.3 主区域

- 服务拓扑。
- Health。
- Latency。
- Error Rate。
- Recent Incidents。
- Audit / Log。

### 54.4 视觉

允许更技术化、更高密度，但仍复用相同 Header、Hairline、Typography 和状态语义。

---

## 55. 模块间直接跳转与返回 Archive

需要区分两种导航。

### 55.1 Workspace → Workspace

例如：

```text
Financial → Risk
Industry Chain → Market
AI Research → Financial
```

使用轻量 `Workspace Crossfade`：

- 约 180–260 ms。
- 保留 Global Research Context。
- 不需要退回档案海重新抽一次。
- Header 中 Module Number / Title 连续切换。

### 55.2 Workspace → Archive

只有用户触发：

```text
RETURN TO ARCHIVE
```

才执行完整链路：

```text
Workspace Fade Out
→ Module Card remains lifted
→ Align at altitude
→ Camera Return
→ Descend
→ FOCUSED Archive
```

两种导航不能混用同一个简单 `switchTab()`。

---

## 56. 返回 Archive 的状态保持

返回主档案终端必须保留：

- 当前 module key。
- lane。
- row。
- 当前 archive track position。
- 每 lane 的 selection memory。
- Global Research Context。

返回后：

```text
interactionState = FOCUSED
environmentState = AWAKE
lastInteractionTime = now
```

禁止重置到 `01 MARKET`，除非用户明确执行 Home / Reset Archive。

---

## 57. 全局视觉 Token

建议统一为一组 CSS / Theme Token，不允许页面各自硬编码不同颜色体系。

### 57.1 基础 Token

| Token | 建议语义 | 参考方向 |
| --- | --- | --- |
| `--archive-paper` | 主背景 | 暖灰 / 象牙白 |
| `--archive-paper-deep` | 次级背景 | 稍深暖灰 |
| `--archive-ink` | 主文字 | 近黑 |
| `--archive-ink-muted` | 次级文字 | 暖灰黑 |
| `--archive-ink-faint` | 注释 | 低对比灰 |
| `--archive-line` | 普通细线 | 暖灰线 |
| `--archive-line-strong` | 主分隔 | 深一档暖灰 |
| `--archive-amber` | 系统聚焦 | 暗金 / 琥珀灰 |
| `--archive-glass` | 档案实体 | 暖白 |
| `--archive-fog` | 场景雾 | 暖灰白 |
| `--semantic-positive` | 金融正向 | 受控绿色 |
| `--semantic-negative` | 金融负向 / Sell | 受控红色 |
| `--semantic-risk` | 风险 | 琥珀红 / 红 |

### 57.2 琥珀色用途

主要表示：

- 当前系统选择。
- 当前模块。
- 档案聚焦。
- 非金融语义的 active control。

不能用琥珀色替代 Buy / Sell 的金融语义。

### 57.3 深色页面

模拟交易、图表等局部区域允许使用深色专业画布，但必须被统一 Archive Shell 包裹，且 Header / Navigation / Return 行为保持一致。

---

## 58. Typography 与信息层级

### 58.1 三级字体系

1. System / Index：等宽或技术感字体，小字号、较大字距。
2. Module / Page Title：MiSans / system sans，中高字重。
3. Data / Body：可读性优先，避免过度等宽。

### 58.2 数字

可在：

- Module No.
- Archive Counter。
- Price / Metrics。
- Timer。

使用等宽数字。

### 58.3 禁止

- 所有文字都使用超小号等宽字体。
- 为“科技感”大量使用全大写中文旁边堆英文。
- 页面标题使用夸张渐变字。

---

## 59. 统一组件系统

建议建立以下复用组件：

```text
ArchiveWorkspaceShell
ModuleWorkspaceHeader
ModuleIndexBar
GlobalContextBar
TerminalSection
HairlineDivider
MetricStrip
DataTable
StatusLabel
EvidencePanel
ArchiveDrawer
TerminalModal
ReturnToArchiveButton
```

### 59.1 TerminalSection

默认：

- 无大圆角。
- 背景透明或极弱色差。
- 通过 1px 细线、留白、标题建立层级。

### 59.2 MetricStrip

多个指标优先使用一条连续指标带，不要每个指标单独做一个大卡片。

### 59.3 Modal / Drawer

进入约 300 ms，退出约 200 ms，可从当前透明度中途反向，延续 RhineLabUI `SurfaceTransition` 的行为原则。

---

## 60. 明确禁止的 AI Dashboard 视觉模式

总构建中明确禁止以下回退：

- 一屏十几个大圆角卡片。
- 每个区域都有独立渐变背景。
- 青绿霓虹边框作为全站默认强调。
- 大面积玻璃毛玻璃 Card Stack。
- 所有按钮做胶囊形。
- 每个指标一个独立图标卡。
- 过量阴影制造“浮卡”。
- 业务页之间使用完全不同的 UI 风格。

优先级应是：

```text
空间关系
→ 留白
→ 细线
→ 文档结构
→ 数据表 / 图表
→ 少量必要卡片
```

---

## 61. 桌面响应式规则

### ≥1440 px

- 完整 Archive Workspace 多栏布局。
- Module Index 显示 5–8 个相邻模块，当前项处于优选区域。
- 右侧 Context / Evidence 可常驻。

### 1100–1439 px

- 收窄侧栏。
- 次级 Evidence 可以变为可展开区域。
- Module Index 继续横向滚动。

### 768–1099 px

- 两栏优先。
- 第三栏移至下方或 Drawer。
- 主图表 / 图谱仍优先占宽度。

不得简单把桌面所有栏按比例压小。

---

## 62. 移动端业务页规则

主档案场在 390 / 430 px 下继续保留 Three.js Archive Sea，但业务 Workspace 改为真正的纵向文档布局。

### 62.1 Market

```text
Quote
↓
Chart
↓
Indicator Tabs
↓
Watchlist / News
```

### 62.2 Sim Trade

```text
Quote
↓
Chart
↓
Order Ticket
↓
Positions / Orders / History Tabs
```

### 62.3 Financial

```text
Company File
↓
Metric Strip
↓
Statement Tabs
↓
Quality Analysis
↓
Risk Flags
```

### 62.4 Industry Chain

- 图谱占上半屏。
- Node File 使用 Bottom Sheet / Drawer。

### 62.5 AI Research

- Task / Analysis / Evidence 改为三个页签或纵向段落。
- Evidence 不得通过极窄第三栏硬塞。

### 62.6 Return to Archive

手机端始终保留明确返回入口，至少满足 44px 触摸高度。

---

## 63. 页面状态与数据语义

所有 Workspace 必须明确区分：

```text
LOADING
LIVE
STALE
CATALOG
FALLBACK
ERROR
EMPTY
```

禁止：

- API 失败时用假数据伪装 LIVE。
- Fallback 模式继续显示看起来像真实报价的随机数字。
- Loading 和 Empty 共用同一个空白页面。

状态应进入统一 `StatusLabel` / System HUD。

---

## 64. 空状态、错误和降级

错误页面仍属于 Analysis OS，而不是浏览器式 Alert。

推荐：

```text
DATA CHANNEL INTERRUPTED
MARKET API / FALLBACK

Retry
Return to Archive
```

### 64.1 WebGL Fallback

主场景 WebGL 不可用时：

- 使用可访问 Module Index + Archive List。
- 保留进入业务模块能力。
- 不阻止整个 JARVIS 使用。

---

## 65. 可访问性

### 65.1 Three.js

Three.js canvas 不能是唯一模块入口。

必须存在 DOM 可访问映射：

- Module Index。
- 隐藏或可见的模块列表。
- 键盘导航。

### 65.2 Focus

进入 Workspace 后：

- 焦点转到页面主标题 / Primary Region。

返回 Archive：

- 焦点回到对应 Module Index / Access Module 控件。

### 65.3 Reduced Motion

继续遵守 Part I 规则，同时所有业务页转场必须支持简化。

---

## 66. 前端架构补全

推荐最终目录：

```text
frontend/src/analysis-os/
  data/
    modules.js
  state/
    archiveStore.js
    researchContext.js
  motion/
    archiveMomentum.js
    archiveIdle.js
    archiveTransition.js
  components/
    ModuleArchiveScene.vue
    ModuleArchiveHud.vue
    ModuleIndexBar.vue
    ArchiveWorkspaceShell.vue
    GlobalContextBar.vue
    ReturnToArchiveButton.vue

frontend/src/pages/
  MarketPage.vue
  CrossMarketView.vue
  BacktestPage.vue
  SimTradeView.vue
  AiCenter.vue
  SentimentPage.vue
  FinancialReportPage.vue
  ChainPage.vue
  RiskPage.vue
  StrategyPage.vue
  OpsView.vue
```

不要求一次移动现有所有文件；但逻辑职责必须逐步收敛到上述边界。

---

## 67. 页面切换状态模型

建议统一：

```ts
type WorkspaceTransitionState =
  | 'ARCHIVE'
  | 'EXTRACTING'
  | 'ENTERING_WORKSPACE'
  | 'WORKSPACE_ACTIVE'
  | 'CROSS_MODULE'
  | 'EXITING_WORKSPACE'
  | 'RETURN_ALIGN'
  | 'RETURN_DESCEND'
```

`activeTab` 可以继续作为兼容层，但不应再成为视觉/动画的唯一状态源。

---

## 68. 实施顺序 V1.1

在 Part I Phase 1–4 基础上，补充执行顺序：

### Stage A — Master Shell

1. `modules.js` 固定 11 个模块 descriptor。
2. Module Index。
3. Global Research Context。
4. Archive Workspace Shell。
5. Return to Archive 状态链。

### Stage B — 代表性三页

优先完整重做：

1. Market。
2. Sim Trade。
3. Financial。

原因：分别覆盖图表型、交易操作型、档案文档型三种最关键页面。

完成后确认视觉语言，再扩展其他页面。

### Stage C — Research / Intelligence

1. AI Research。
2. Bull–Bear。
3. Industry Chain。
4. Risk。

### Stage D — Strategy / System

1. Backtest。
2. Strategy Foundry。
3. Cross Market。
4. Ops。

### Stage E — Full Regression

- Auth。
- Market API。
- SSE。
- Sim Trading。
- Stop Order。
- CSRF。
- Responsive。
- Reduced Motion。
- Archive lifecycle。

---

## 69. 全站新增验收清单

### A. Module Index

- [ ] 11 个模块可通过可滚动 Module Index 访问。
- [ ] Archive 滚动时 Index 自动跟随。
- [ ] 点击非当前项只定位 / Focus。
- [ ] 点击已 Focus 项进入 Workspace。
- [ ] 390px 不产生页面级横向溢出。

### B. Archive Module Model

- [ ] Three.js 实体不再以股票资产作为一级模块卡。
- [ ] 每张模块卡编号稳定。
- [ ] Focus HUD 显示模块名、说明、能力和进入操作。
- [ ] 业务数据不会被硬画进每张 3D 卡造成信息过载。

### C. Workspace Shell

- [ ] 11 页都显示 Module No / Title / Context / Status / Return。
- [ ] 页面视觉 Token 一致。
- [ ] 页面可以有不同布局，但 Shell 不分裂成 11 套设计。

### D. Global Context

- [ ] Market 选择资产后进入 Financial 保持标的。
- [ ] Financial → Risk 保持 Context。
- [ ] Industry Chain 节点可更新 Context。
- [ ] 不兼容页面不清除 Context。

### E. Market

- [ ] 图表是主画布。
- [ ] Watchlist 不抢占主视觉。
- [ ] 行情状态和数据时效清晰。

### F. Sim Trade

- [ ] 图表优先。
- [ ] Order Ticket 不永久压缩主图表。
- [ ] Positions / Orders / Stops / History 可用。
- [ ] 现有交易逻辑完整回归。

### G. Financial

- [ ] 公司档案头完整。
- [ ] 三表页签一致。
- [ ] Quality / Risk 区分明确。

### H. AI Research

- [ ] 不以聊天气泡为核心布局。
- [ ] Thesis / Evidence / Counter Evidence / Risk / Conclusion 结构清晰。
- [ ] Evidence 可追踪。

### I. Bull–Bear

- [ ] 多空双方结构对称。
- [ ] 有明确失效条件。
- [ ] 最终 synthesis 不做无依据单边结论。

### J. Industry / Risk / Strategy / Ops

- [ ] 图谱页真正以图谱为主。
- [ ] 风险页不是红卡片墙。
- [ ] Strategy 可以带参数进入 Backtest。
- [ ] Ops 展示真实服务状态。

### K. Mobile

- [ ] Market 430px 正常纵向重排。
- [ ] Sim Trade 430px 可下单且图表可用。
- [ ] Financial 390px 不强塞桌面三栏。
- [ ] Industry Node Detail 使用 Drawer / Bottom Sheet。
- [ ] `RETURN TO ARCHIVE` 触摸目标合格。

### L. Visual Quality

- [ ] 不出现全站大面积青绿霓虹。
- [ ] 不出现一屏大量大圆角 AI 卡片。
- [ ] 细线、留白、文档结构成为主要层级语言。
- [ ] 金融语义色与系统选择色严格分离。

---

## 70. Master Definition of Done

V1.1 完成不再只判断“档案海是否好看”。必须同时满足：

1. 登录后以 Archive Hub 作为第一工作界面。
2. 主场景拥有 Part I 定义的真实滚动、惯性、睡眠、唤醒和抽取。
3. Module Index 与 Three.js 档案完全双向同步。
4. 11 个业务模块全部通过 Archive Workspace Shell 统一。
5. 代表性页面不再呈现普通 AI Dashboard 卡片风格。
6. Global Research Context 可以跨相关模块继承。
7. Workspace → Workspace 与 Workspace → Archive 使用不同、正确的状态链。
8. Market / Sim Trade / Financial 三种核心页面形态完成高质量实现。
9. 其他 8 个模块完成统一视觉和信息架构迁移。
10. 桌面、平板、390 / 430 px 移动端均有明确重排，不依靠整体缩放。
11. Reduced Motion、WebGL Fallback 和键盘访问可用。
12. 现有认证、CSRF、市场 API、SSE、回测、模拟交易、Stop Order 等业务能力不退化。
13. 没有重复 RAF、WebGL context 或页面切换资源泄漏。
14. 所有页面状态明确区分 LIVE / STALE / FALLBACK / ERROR / EMPTY。
15. 最终人工视觉验收确认：从 Archive 海到业务 Workspace 属于同一个产品世界，而不是“高级 3D 首页 + 普通后台网站”。

只有达到上述条件，才允许把 `JARVIS Analysis OS Master Rebuild` 标记为完成。

---

## 71. 2026-09-14 · Interaction Rebuild V2 实施检查点

当前实现分支：`feat/analysis-os-interaction-rebuild-v2`。

本检查点用于区分“已经落地”与“仍属于 Master DoD 的后续工作”，不能替代第 70 节最终验收。

### 71.1 已实现并通过当前自动回归

- [x] 登录后默认进入 JARVIS Analysis OS Archive Hub。
- [x] 主档案场从市场资产卡改为 11 个业务模块档案。
- [x] 建立 5 个 lane 的模块分类和周期映射。
- [x] 自由二维拖动使用相机投影轨道反解，而不是 CSS carousel。
- [x] 松手速度使用短时间窗采样，并实现 coasting → snapping。
- [x] 滚轮按累计阈值切换 row，并支持停止/反向重置累计。
- [x] 顶部 Module Index 可横向滚动，并与 Three.js 当前模块双向同步。
- [x] Focused 浅抬升与完整 extraction 分离。
- [x] 完整抽取由卡片 Y 轴升高和相机重构共同完成；未通过横向飞卡重新构图。
- [x] 抽取进度驱动 ACCESSING MODULE 状态，而不是固定时间后突然切页。
- [x] RETURN TO ARCHIVE 保持抽取卡状态，执行归档下降后回到当前模块。
- [x] Workspace 内直接跨模块使用独立轻量 crossfade，不强制经过 Archive Hub。
- [x] Workspace 跨模块后返回 Archive 时，Three.js 物理焦点同步为实际当前模块。
- [x] Idle Controller 独立于 Three.js，包含 8s / 12s / 18s 三段计时。
- [x] Sleep Drift 是视觉偏移层，不直接改写逻辑业务模块。
- [x] Sleep 中 pointer/wheel 可捕获当前视觉位置并交回用户控制。
- [x] Sleep Drift 增加档案海起伏、低速 row/lane 漂移与轻微镜头呼吸。
- [x] `prefers-reduced-motion` 下限制持续自主运动。
- [x] 页面隐藏/非当前 Archive Tab 时停止主场景 RAF。
- [x] 11 个业务模块接入统一 Archive Workspace Shell，旧顶部 Tab 不再作为普通业务导航。
- [x] Market / Cross Market / Backtest / Sim Trade / AI Research / Bull-Bear / Financial / Industry / Risk / Strategy / Ops 完成第一轮 Archive 信息语言与视觉 Token 迁移。
- [x] Market 采用浅色 Archive Shell + 图表主画布 + 连续 Quote/Data Health 信息栏。
- [x] Sim Trade 采用浅色 Archive Shell + 深色专业交易核心，保留 Buy/Sell 金融语义色。
- [x] Financial 采用 Source Document → Processing → Analysis Dossier 流程，未伪造后端没有提供的结构化财务数字。
- [x] Global Research Context 已接通 Market / Cross Market / Sim Trade，并显示在 Workspace Shell。
- [x] 430px / 390px Archive Hub 与 Sim Trade 当前实测无 document 横向溢出。
- [x] 暴露 `window.__jarvisArchiveDebug` 只读调试状态，便于自动验收 lane/row、惯性、sleep、extraction 和 canvas 生命周期。
- [x] 当前 P0 自动回归 37 / 37 通过，Vite production build 通过。

### 71.2 本轮实际浏览器验收已完成

- [x] Sleep 等待后可进入 `SLEEP_DRIFT`；交互后恢复 `AWAKE`。
- [x] Wheel 能从当前模块切到相邻 row，不是单纯替换文字。
- [x] 横向 drag 可以切换 lane，并在松手后继续惯性运动。
- [x] 抽取中间帧可观测 `EXTRACTING` 和连续 extractionProgress，档案实体真实升高。
- [x] `Archive → SIM TRADE Workspace → RETURN` 主链路可执行。
- [x] `SIM TRADE Workspace → FINANCIAL Workspace → RETURN` 后 Archive 正确聚焦 07 FINANCIAL。
- [x] 代表截图检查了 Archive 1440、抽取中间态、Market、Financial、Sim Trade、Archive 430、Sim Trade 430。
- [x] Playwright 验收用模拟交易数据只存在浏览器 route 层，没有写入项目、数据库或生产逻辑。

### 71.3 仍未达到 Master DoD 的部分

- [ ] Industry Chain 仍需从文本分析页升级为真正以关系图谱为主的交互画布，并实现 Node Detail Drawer / Bottom Sheet。
- [ ] Strategy 仍需实现明确的 `SEND TO BACKTEST` 参数交接闭环。
- [ ] Global Research Context 需继续让 Financial / Industry / Risk / AI Research 等页面主动消费，而不只是由 Shell 展示。
- [ ] 各模块的 LIVE / STALE / FALLBACK / ERROR / EMPTY 需要逐页统一成最终组件，而不是仅依赖现有页面错误态。
- [ ] 需要在真实 Java 后端和真实认证会话下重新完成 Market、SSE、Sim Account、Stop Order 等端到端业务验收。
- [ ] 需要重新进行隐藏 Archive RAF、重复进出 Workspace 和 WebGL context 数量的长循环性能验收。
- [ ] 需要补全键盘 focus order、ARIA live region 和最终 Reduced Motion 人工验收。
- [ ] 需要对 1024 / 1280 / 1440 / 390 / 430 全模块页面完成最终像素级视觉审阅，而非只检查代表页面。
- [ ] 需要最终用户确认主档案场的相机、档案密度、睡眠幅度和抽取节奏，再允许合并到上游 main。

因此当前状态应标记为：**Interaction Rebuild V2 已形成可运行的完整主链与全站第一轮 UI 迁移，但 Master Rebuild 尚未完成最终验收。**

