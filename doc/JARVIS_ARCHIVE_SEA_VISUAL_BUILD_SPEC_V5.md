# JARVIS Analysis OS：Archive Sea Visual Build Spec V5

版本：V5.0  
状态：视觉需求冻结稿 / 待实现  
适用范围：登录后的 JARVIS Analysis OS 主档案终端，以及从档案终端进入 Workspace 的视觉过渡  
不适用范围：后端 API、业务算法、真实随机检索、真实数据库搜索、交易规则、认证逻辑

---

## 0. 文档目的

本文件只定义“用户应该看到什么”和“动画应该如何呈现”。

它不要求为了制造真实感去增加复杂计算，也不要求实现真实随机档案选择算法。

最终目标是：

> 让用户感觉自己正在操作一个真实存在的金融研究档案终端，系统会在一个巨大的档案海中滚动、筛选、识别、抽取文件，然后进入对应的业务工作区。

视觉真实感必须主要来自：

- 相机；
- 构图；
- 档案海密度；
- 前中后景；
- 双向循环滚动；
- 查询 / 命中 / 聚焦节奏；
- 玻璃与材质；
- 景深与雾化；
- HUD 层级；
- 抽取和返回时间线。

而不是来自：

- 复杂随机算法；
- 大规模候选评分；
- 每帧搜索所有档案；
- 每张档案都加载高细节 GLB；
- 大量额外 WebGL 对象。

---

# 1. 核心视觉定义

## 1.1 主场景不是“档案矩阵”，而是“进入档案海”

首页第一视觉必须让用户感觉：

> 镜头已经处在档案阵列内部。

禁止形成以下感觉：

- 从高处俯视一个规则矩阵；
- 站在文件柜外面看很多文件夹；
- 一个 Three.js 模型展示器；
- 一张普通网页后面放了一个 3D 背景。

正确画面必须具有：

- 前景档案被视口裁切；
- 中景档案承担检索与 Focus；
- 远景档案连续延伸；
- 左右画面边缘仍然有档案继续出画；
- 当前文件不是屏幕上唯一对象；
- 背景始终存在“巨型档案库”的空间感。

---

## 1.2 前景 / 中景 / 远景固定职责

### 前景

视觉职责：制造“用户已经进入档案海”的包围感。

要求：

- 允许被视口裁切；
- 以顶部、边缘、侧面为主；
- 不需要完整显示正面；
- 少标签或无标签；
- 可轻微失焦；
- 不允许出现几块巨大的完整空白白板挡住屏幕。

### 中景

视觉职责：承担系统筛选、命中、聚焦和用户主要交互。

要求：

- 最清晰；
- 当前检索出的文件在此出现；
- QUERY / MATCH / FILE NUMBER 对应此区域；
- 当前 Focus File 应与周边形成明显但克制的层级差。

### 远景

视觉职责：表达档案库的规模和连续性。

要求：

- 继续延伸；
- 边线更淡；
- 透明度 / 雾化增强；
- 可使用轻微 DOF；
- 不需要可读文字。

---

# 2. 档案海形态

## 2.1 背景档案形成“坡面”，不是垂直书架

普通档案不能全部保持 90° 竖直。

背景档案需要：

- 统一轻微后倾；
- 不同 row 有轻微高度差；
- lane 之间存在轻微 Z 方向错位；
- 形成连续斜向坡面；
- 视觉上更接近白色档案浪潮，而不是书架。

当前被选中的文件才逐渐接近竖直。

建议视觉范围：

- 背景后倾约 12°–16°；
- Focus 接近 0°；
- Hover 可以比背景稍微回正，但不能跳变。

以上数字为视觉基准，可继续按最终截图微调。

---

## 2.2 档案海必须保留空气感

整体密度应低于当前“文件墙”版本。

要求：

- Row 间距足够产生可见空气层；
- Lane 之间不形成完全平行的墙；
- 留出浅色负空间；
- 不让整个视口铺满完整矩形；
- 当前文件附近自动形成阅读净空。

禁止：

- 所有档案贴得太近；
- 所有档案正面都完整可见；
- 画面下半部分全部被大白面板覆盖。

---

## 2.3 背景档案默认匿名

绝大多数档案不得长期显示业务模块名称。

背景档案默认只表现为：

- 白色档案结构；
- 极淡的编号；
- 极淡分类痕迹；
- 或完全无文字。

不得在背景中同时大面积出现：

- MARKET；
- BACKTEST；
- RISK；
- FINANCIAL；
- AI RESEARCH；
- 其他业务页面名。

用户应该感觉：

> 这些档案都是待检索的数据对象，而不是已经绑定页面的菜单按钮。

---

# 3. 模块身份与档案视觉解耦

## 3.1 视觉规则

业务模块不应在视觉上永久属于某张物理档案。

禁止形成：

> MARKET 永远是中央固定这一张档案。

正确表现：

> 用户切换到 MARKET 时，系统在当前档案流中“识别”一张即将进入焦点区域的匿名档案，然后在这张档案上显现 MARKET 信息。

下一次回到 MARKET 时，可以由另一张物理档案承载。

注意：

这里不要求真实随机算法。

只要求用户从视觉上无法形成“模块永远固定在某个槽位”的印象。

---

## 3.2 系统筛选感只通过视觉制造

需要让用户感觉：

> 系统刚刚从档案海中找到了一份目标文件。

但底层可以非常简单。

视觉过程：

1. 档案海开始向用户操作方向滚动；
2. 若干匿名档案经过中景；
3. HUD 出现极轻的 QUERY 状态；
4. 某一张即将进入 Focus Zone 的档案出现短暂扫描；
5. HUD 变为 MATCH FOUND；
6. 模块身份在该档案上显现；
7. 档案进入 Focus；
8. 显示 ACCESS FILE。

不要求真实“搜索”。

不允许为此增加复杂候选评分算法。

---

# 4. 双向循环滚动

## 4.1 正确的循环定义

这是双向循环，不是单向循环。

例如当前逻辑模块是 05：

```text
← / 向前 / Wheel Up
05 → 04

→ / 向后 / Wheel Down
05 → 06
```

首尾循环：

```text
01 向前 → 11
11 向后 → 01
```

所以用户永远可以双向浏览，且没有终点。

---

## 4.2 动画方向必须对应用户输入方向

用户向左 / 向前：

- 档案海按该方向运动；
- 当前逻辑模块索引 -1；
- 目标文件从该方向进入 Focus Zone。

用户向右 / 向后：

- 档案海按相反方向运动；
- 当前逻辑模块索引 +1；
- 目标文件从对应方向进入 Focus Zone。

禁止：

- Previous 和 Next 最终都向同一个物理方向滚；
- 逻辑上一页但画面仍向下一页方向滚；
- 到边界突然停住。

---

## 4.3 连续滚动时保持流动感

用户快速滚轮 / 拖动时：

- 档案海应该连续运动；
- 可以连续跨多张；
- 不要每经过一个模块都完整播放 QUERY / MATCH；
- 不要每经过一张都抬升；
- 不要不断打断惯性。

只有在速度开始降低、即将停止时：

```text
FLOW
→ QUERY
→ MATCH
→ SNAP
→ FOCUS
```

这样用户才会感觉是一个真实的检索终端，而不是逐页轮播组件。

---

# 5. Archive Retrieval Illusion

## 5.1 定义

正式名称：

> Archive Retrieval Illusion / 档案检索视觉幻觉

目标：

> 用户感觉系统正在筛选和确认文件，但实现不需要做复杂随机检索。

---

## 5.2 查询阶段视觉

QUERY 阶段必须轻量。

允许：

- 当前 Focus Zone 附近 1–3 张档案边缘出现短暂亮度变化；
- 一条非常细的扫描线；
- HUD 出现 `ARCHIVE QUERY`；
- 小型状态点；
- 极淡琥珀色定位提示。

禁止：

- 多张档案疯狂闪烁；
- 抽卡手游效果；
- 大量数字滚屏；
- 黑客终端瀑布文字；
- 高饱和霓虹光。

---

## 5.3 命中阶段视觉

MATCH FOUND 后：

- 目标档案开始回正；
- 稍微抬升；
- 周边档案轻微下沉形成净空；
- 当前档案材质对比增强；
- 动态模块标签开始显现；
- HUD 从 QUERY 切换到 FILE NUMBER。

建议文案：

```text
ARCHIVE QUERY
SEARCHING…

MATCH FOUND
FILE NUMBER: MK-01
```

但所有文字尺寸必须小，不可盖过档案实体。

---

# 6. Focus File 视觉

## 6.1 当前文件不能靠巨大放大来强调

Focus 应主要通过：

- 回正；
- Y 轴抬升；
- 周边净空；
- 清晰度增强；
- 玻璃材质变化；
- 琥珀定位线；
- 标签显现。

而不是：

- 放大两倍；
- 飞向镜头；
- 脱离整个档案海。

---

## 6.2 Focus GLB

保留现有原创 Blender / GLB 方案。

同一时刻最多一个高细节 Focus GLB。

Focus GLB 视觉要求：

- 透明玻璃；
- 浅暖灰框；
- 内部双环；
- 导轨；
- 锁扣；
- 紧固件；
- 少量琥珀色 spine / 定位结构；
- 动态模块标签。

背景档案继续使用低成本 LOD。

---

## 6.3 琥珀色规则

琥珀 / 暗金只表达：

> 系统当前正在定位、锁定、确认的对象。

只允许用于：

- 当前 Focus File；
- MATCH 状态；
- 很小的定位条；
- 解密导向线；
- 极少量交互强调。

禁止变成页面主色。

---

# 7. 浏览态 HUD

## 7.1 HUD 必须低于 3D 场景视觉权重

浏览态不再使用大标题 Hero。

正确结构示例：

```text
FILE NUMBER: MK-01

MARKET / 行情
────────────────────────

ACCESS FILE →
```

说明文字 / capabilities 只作为极淡的次要信息。

视觉权重顺序必须是：

```text
Focus File
>
Archive Sea
>
FILE NUMBER
>
Module Name
>
Summary / Capabilities
```

---

## 7.2 阅读净空

FILE NUMBER 必须尽量落在中景负空间上。

目标档案右侧需要形成阅读净空：

- 周边档案轻微下沉；
- 不需要消失；
- 仍能看出档案海连续存在；
- 但 HUD 后方不能是一堵密集矩形墙。

---

# 8. Module Index 视觉

## 8.1 默认折叠

顶部不应常驻 11 个完整模块。

默认只显示：

```text
MODULE INDEX      05 / AI RESEARCH
```

右侧保留：

```text
SEARCH   SYNC   SOUND   STATUS   TIME
```

点击 MODULE INDEX 后才展开完整列表。

---

## 8.2 Module Index 只表示逻辑模块

Module Index 不应跟物理档案 row/lane 同步滚动。

它只表达：

- 当前模块；
- 用户想去哪个模块。

Three.js 物理档案怎么循环复用，不应该导致顶部导航乱跳。

---

## 8.3 展开态仍然必须克制

展开后的 11 模块列表：

- 细线；
- 小字号；
- 横向可滚；
- 不使用大块按钮；
- 不使用卡片式导航；
- 当前项只做轻微高亮。

关闭后回到单行状态。

---

# 9. 双向翻页与顶部导航的关系

用户通过滚轮 / 拖动 / ← →：

- 先发生档案海运动；
- 逻辑模块跟随最终落点变化；
- Module Index 只更新当前编号和模块名。

用户直接点击 Module Index 某模块：

- 档案海以合理方向滚动；
- 中间仍经过 FLOW / QUERY / MATCH；
- 不允许直接瞬切文字；
- 不允许整个阵列瞬移。

---

# 10. 快速连续操作

快速滚动时：

- 不每页播放完整检索；
- 不每页抬升 GLB；
- 不每页展开 HUD；
- 保持档案流。

当输入停止并开始吸附时：

- 只对最终模块播放 QUERY / MATCH；
- 只实例化一个 Focus GLB；
- 只显示最终 FILE NUMBER。

---

# 11. Sleep / Idle 视觉

## 11.1 睡眠态不是屏保

同一档案海继续存在。

无操作后：

- 档案海低速漂移；
- 有轻微上下呼吸；
- 相机极轻浮动；
- HUD 降亮；
- Focus 强调弱化；
- 不播放明显的“睡眠动画页面”。

---

## 11.2 唤醒

用户向哪个方向拖 / 滚，就从当前画面立即接管该方向。

禁止：

- 先强制回到某个固定模块；
- 先停止再跳转；
- 反方向倒车。

唤醒后：

```text
Sleep Drift
→ User Flow
→ Query
→ Match
→ Focus
```

---

# 12. 光照与材质

## 12.1 不能再用“整屏加白”制造高级感

深度必须来自：

- 接触阴影；
- 环境遮蔽；
- 玻璃边缘反射；
- 前中后景亮度差；
- 远景 Fog；
- 景深；
- 材质粗糙度变化。

禁止：

- 过高曝光；
- 大面积白色 overlay；
- 浓 Fog 把所有东西漂白。

---

## 12.2 玻璃

玻璃必须表现得“轻”。

浏览态：

- 半磨砂；
- 背景仍可看到；
- 轮廓清晰。

Focus：

- 边缘反射更明显；
- 透明度略提升。

Decrypt：

- 从磨砂逐步清晰；
- 内部双环和导轨逐渐显现。

不能像灰色塑料板。

---

# 13. 景深 / DOF

桌面 HIGH 视觉建议：

- Focus File：始终清晰；
- 附近 1–2 层档案：基本清晰；
- 远景：轻微失焦；
- 极近前景：允许少量失焦。

DOF 目标：

> 让画面像摄影镜头，而不是 3D 编辑器视口。

移动端 / Reduced Motion / 性能不足时可以关闭。

---

# 14. Extraction 时间线

点击 ACCESS FILE 后保留现有 V3 链路：

```text
RELEASE LOCK
↓
VERTICAL EXTRACTION
↓
CAMERA APPROACH
↓
GLASS DECRYPT
↓
DOCUMENT REVEAL
↓
WORKSPACE BRIDGE
↓
WORKSPACE
```

---

## 14.1 档案只主要沿 Y 轴抽取

档案本身不要向相机飞。

主要运动：

- Y 轴抬升；
- 轻微回正；
- 玻璃变化。

真正完成“靠近”的是相机。

---

## 14.2 抽取时档案海必须保留

点击以后不能：

```text
Archive Sea
→ 突然消失
→ 单独一张卡
```

必须：

```text
Archive Sea 仍然存在
+
Focus File 抽取
+
Camera Reframe
```

用户必须一直知道：

> 这份文件就是刚才从这片档案海里抽出来的。

---

# 15. Workspace Bridge

进入真正业务页之前继续保留桥接态。

桌面：

- 左侧保留当前档案视觉锚点；
- 右侧出现模块资料；
- 保持约半秒；
- 再切入真实业务 Workspace。

移动端：

- 取消大档案左栏；
- 只保留轻量模块详情桥接；
- 不产生横向溢出。

---

# 16. Return to Archive

返回动画：

```text
Workspace Fade
↓
File Becomes Primary
↓
High Align
↓
Glass Reset
↓
Descend
↓
Archive Sea
```

返回后：

- 档案重新进入档案海；
- 模块身份可以淡出；
- 视觉上重新成为匿名档案；
- 不重置到第一个模块；
- 当前逻辑模块仍保持。

---

# 17. 开场视觉

继续保留原创 JARVIS Opening：

```text
SYSTEM WAKE
→ IDENTITY
→ PERMISSION SCAN
→ MODULE ARCHIVE ONLINE
→ SYSTEM READY
```

要求：

- 最后不是硬切；
- READY 层渐隐；
- 档案海从下层接管画面；
- 移动端无横向溢出；
- Reduced Motion 快速跳过。

---

# 18. 声音视觉配合

声音系统继续保持原创 WebAudio。

只作为以下视觉节点的轻反馈：

- Wake；
- Focus；
- Query Match；
- Extract；
- Decrypt；
- Reveal；
- Return。

声音默认关闭。

不加入第三方游戏音效。

---

# 19. 性能边界

视觉真实感必须优先通过构图和时序获得，而不是计算复杂度。

硬性原则：

- 不实现真实随机档案搜索；
- 不做复杂候选评分；
- 不每帧遍历并计算所有档案可见面积；
- 不为每张档案加载 GLB；
- 不增加第二个 WebGL context；
- 同时最多一个高细节 Focus GLB；
- QUERY / MATCH 只修改焦点附近少量现有对象；
- 背景继续使用程序化 LOD；
- 移动端不加载 Focus GLB；
- 隐藏 Archive 时 RAF 必须停止。

目标：

- 桌面 HIGH：保持接近当前 60 FPS 基线；
- MOBILE：保持单 canvas；
- Archive ↔ Workspace 多次往返不得增加 canvas；
- Sleep/Wake 不创建新对象池。

---

# 20. 当前已有能力必须保留

V5 实现不得破坏：

- 双向滚轮；
- 拖动；
- 惯性；
- 吸附；
- Sleep/Wake；
- Reduced Motion；
- 原创 Blender Focus GLB；
- SSAO / post-processing fallback；
- Glass Decrypt；
- Document Reveal；
- Workspace Bridge；
- Return Align / Descend；
- Global Research Context；
- 11 个业务 Workspace；
- `?preview=1` 本地开发预览；
- 移动端布局；
- 单 canvas 生命周期。

---

# 21. 明确废弃的旧设计

以下旧方案不得继续沿用：

1. 某个业务模块永久绑定某张物理档案。
2. MARKET 永远出现在一个固定 cell。
3. Previous / Next 都让档案海向同一方向滚。
4. 为了“伪随机”实现复杂候选池 / 评分 / 搜索算法。
5. 顶部 Module Index 跟随物理 row/lane 横向乱滚。
6. 背景大量档案同时显示可读模块文字。
7. 所有档案垂直站立形成文件夹墙。
8. 浏览态使用大 MARKET Hero 标题压住场景。
9. 点击后背景档案海立刻消失。
10. 为了高级感增加整屏白雾。

---

# 22. V5 实施阶段

## Phase V5-A · Archive Sea Recomposition

目标：先把主页做成真正的档案海。

- 相机进一步进入档案阵列；
- 前景裁切；
- 背景坡面；
- 行列空气感；
- 远景柔化；
- 减少标签；
- 阅读净空。

验收：

- 静态截图第一眼不再像文件夹墙；
- 参考视频 52s 的空间关系成立。

---

## Phase V5-B · Cyclic Flow

目标：建立正确的双向循环视觉。

- Previous → 向前；
- Next → 向后；
- 01 ↔ 11 首尾循环；
- 连续滚动不反复播放 Query；
- 停止时才进入 Query / Match。

验收：

- 当前 05，前滚得到 04；
- 当前 05，后滚得到 06；
- 01 前滚得到 11；
- 11 后滚得到 01。

---

## Phase V5-C · Retrieval Illusion

目标：让用户感觉系统在真实检索。

- Anonymous Flow；
- Query HUD；
- 极轻 Scan；
- Match Found；
- Module Identity Reveal；
- Focus Lift。

验收：

- 用户看不出模块永久绑定某张卡；
- 快速滚动不会卡顿；
- 停止时“像系统找到了文件”。

---

## Phase V5-D · Optical Fidelity

目标：完成摄影感。

- 玻璃；
- 边缘高光；
- AO；
- 远景 Fog；
- DOF；
- Focus 始终清晰；
- 前景轻失焦。

---

## Phase V5-E · Final Transition Pass

目标：统一 Browse → Query → Match → Focus → Extract → Workspace → Return。

- 消除突变；
- 调整各阶段时间；
- 保证 Archive Sea 始终有来源感；
- 对照参考关键帧重新验收。

---

# 23. 桌面验收标准

## 23.1 Browse

- [ ] 第一眼是“档案海内部”，不是矩阵。
- [ ] 前景被裁切。
- [ ] 背景形成坡面。
- [ ] 大部分背景档案匿名。
- [ ] Focus File 位于中景。
- [ ] FILE NUMBER 位于负空间。
- [ ] 顶部 Module Index 默认折叠。
- [ ] 没有大 Hero 标题。

## 23.2 Flow

- [ ] 两个方向都能滚动。
- [ ] 首尾循环自然。
- [ ] 快速连续滚动保持流动。
- [ ] 不每经过一页就完整检索。

## 23.3 Retrieval

- [ ] 停下来后出现 Query。
- [ ] 经过短扫描后 Match。
- [ ] 模块身份在 Focus File 上显现。
- [ ] 用户感觉“系统找到了文件”。
- [ ] 不能看出真实随机算法不存在。

## 23.4 Focus

- [ ] 只使用一个高细节 GLB。
- [ ] 背景仍存在。
- [ ] 周边有净空。
- [ ] 玻璃可辨识。
- [ ] 内部双环可辨识。
- [ ] 琥珀定位线克制。

## 23.5 Extract / Detail

- [ ] 档案主要沿 Y 抽取。
- [ ] 相机完成靠近。
- [ ] 档案海没有第一帧消失。
- [ ] Decrypt 后进入 Document Reveal。
- [ ] Workspace Bridge 存在。
- [ ] 真正 Workspace 不突兀。

## 23.6 Return

- [ ] Workspace 先淡出。
- [ ] 文件重新成为主体。
- [ ] 高处对齐。
- [ ] 下降回档案海。
- [ ] 当前逻辑模块不重置。

---

# 24. 移动端验收标准

- [ ] 390px / 430px 无 document 横向溢出。
- [ ] 保持档案海，不退化成普通列表。
- [ ] 不加载高细节 Focus GLB。
- [ ] 不强制 DOF / 重后处理。
- [ ] Module Index 放到底部或使用移动端折叠方案。
- [ ] Query / Match 不遮挡主要档案。
- [ ] Workspace Bridge 不使用左右双栏。
- [ ] 单 canvas。

---

# 25. Reduced Motion

开启 Reduced Motion 时：

- 档案仍可双向切换；
- 减少惯性距离；
- Query / Match 缩短；
- 禁用持续 Sleep Drift；
- 禁用强 DOF / 大幅镜头运动；
- Extraction 保留最短的可理解过渡；
- 不直接把体验退化成无状态瞬切。

---

# 26. 最终 Definition of Done

只有同时满足以下条件，V5 视觉重构才算完成：

1. 首页在静态截图上已明显接近“进入档案海”的空间关系。
2. 不再像规则文件夹矩阵。
3. 背景档案绝大部分匿名。
4. 模块身份视觉上不永久绑定具体档案。
5. 双向循环成立，方向与输入一致。
6. 快速滚动不重复播放复杂 Query。
7. 停止时能形成可信的“系统检索 → 命中”幻觉。
8. Focus File 使用高细节 GLB，背景继续轻量 LOD。
9. FILE NUMBER HUD 不抢画面。
10. 顶部 Module Index 默认折叠，并与物理档案位置解耦。
11. Sleep / Wake 与新循环模型兼容。
12. 抽取 / 解密 / Workspace Bridge / Return 均保持连续。
13. 桌面 HIGH 仍接近现有约 60 FPS 基线。
14. MOBILE 单 canvas，无横向溢出。
15. 不引入真实随机搜索算法或额外高成本视觉计算。
16. 不复制 RhineLabUI 的非代码模型、图像、音频或品牌资产。

---

# 27. 一句话产品定义

> JARVIS Analysis OS 不是一个“用 Three.js 做菜单”的金融网站，而是一座可双向循环浏览的金融档案海：用户在连续流动的匿名档案中操作，系统通过克制的检索视觉将某一份文件临时识别为目标模块，再将其抽取、解密并展开为真实业务工作区。

---

# 29. 官方 Archive 参考纠偏记录（2026-09-14）

本节用于冻结本轮直接对照 `LBEILC/RhineLabUI` 官方仓库 `docs/media/archive.jpg`、`scene.ts`、`motion.ts` 与 `viewport-layout.ts` 后得到的浏览态视觉基准。

此前 JARVIS 的主要偏差不是“少滤镜”，而是档案比例、阵列节奏和 Browse 镜头本身错误；后续不得再次回退到旧参数。

## 29.1 档案几何

- [x] 背景档案改为宽约 `5.0`、高约 `3.7` 的横向档案比例。
- [x] 列距固定到约 `5.20`。
- [x] 行距固定到约 `0.62`。
- [x] 基准高度统一为 `Y ≈ -4.60`。
- [x] 浏览态不再人为把整片背景档案大角度后倾。
- [x] 物理档案仍保持匿名，只在当前命中文件显示模块身份。

## 29.2 Browse 镜头

桌面 Browse 不再停留在早期约 6° 的中间镜头，而使用参考实现的 settled 长焦关系：

- [x] `yaw ≈ 59°`。
- [x] `elevation ≈ 19°`。
- [x] `distance ≈ 140`。
- [x] 基准纵向 span `≈ 7.33`。
- [x] `cameraAim ≈ (-1.091, -0.045, 0.481)`。
- [x] FOV 根据 viewport aspect 由 span / distance 动态计算；16:9 约 3°。
- [x] Fog 改为跟随真实渲染相机距离，而不是使用固定世界距离洗白整个前景。

## 29.3 连续肩部波场

参考档案海的可读性来自连续高度场，而不是通过扩大行距或硬挖阅读空洞实现。

- [x] 移除旧的 `foregroundSink / readingVoid` 人工下沉逻辑。
- [x] 加入连续 `archiveShoulderField(row, lane)`。
- [x] 中心肩部高度约 2 world units，并随 row 距离平滑衰减。
- [x] 当前 lane 权重最高，邻列只保留较弱肩部。
- [x] 静置呼吸仍保持低幅度，不与检索肩部争夺视觉层级。
- [x] 当前文件在肩部基础上只额外抬升约 `0.40`。

## 29.4 Focus File 浏览态与高细节资产

- [x] Browse / FOCUSED 阶段使用轻量程序化档案 + 动态标签，不直接显示完整双环 GLB。
- [x] 高细节原创 Blender GLB 只在真正 Extraction 开始后接管。
- [x] 这样浏览态不会出现“一张工业模型突兀地插在档案海里”的视觉断层。
- [x] 焦点标签位于玻璃前方，可在 Browse 状态正常阅读。
- [x] 背景匿名档案增加非常克制的小型识别标记，形成档案海节奏但不暴露业务模块名。

## 29.5 HUD 参考关系

- [x] 顶部 `MODULE INDEX` 默认收拢到右上系统工具区。
- [x] 中央 `FILE NUMBER` 升级为主要 HUD 层级。
- [x] `FILE NUMBER` 上方加入 `INTERNAL DATABASE / <CATEGORY> ARCHIVE` 上下文。
- [x] 左下改为 `ARCHIVE / SELECT`。
- [x] 底部中间只显示当前模块编号和中英文名称，不再显示 `CYCLIC FLOW / FOCUSED` 等调试状态。
- [x] `POWERED BY JARVIS` 保持右下弱权重。

## 29.6 光学适配

- [x] 档案材质向暖米白校准，减少旧版灰黑边框感。
- [x] 玻璃保持轻量透明，不使用灰塑料效果。
- [x] HIGH 档可选 DOF 的焦点距离不再写死为 34，而是每帧跟随 `camera.position.distanceTo(cameraAim)`。
- [x] HIGH 档 `maxBlur` 收紧，避免真实 Edge 把整片档案海糊掉。
- [x] 自动化浏览器仍允许跳过可选后处理；核心构图不得依赖 DOF 才成立。

## 29.7 视觉验收记录

- [x] 官方 `archive.jpg` 仅下载到本地 `.playwright-cli` 验收目录作对照，不进入 Git。
- [x] 1440×900 本地截图已确认：长焦档案海、肩部波场、选中文件露出、中央 FILE NUMBER、右上索引和底部信息关系均已对齐到参考构图语言。
- [x] 430×844 本地截图已确认：横向档案比例和新 HUD 没有破坏移动端档案海，也没有退化成普通列表。
- [ ] 下一阶段继续对照参考视频校准 Extraction / Decrypt / Detail Camera；本节不宣称详情转场已达到最终参考完成度。

## 29.8 无边界 Archive Sea / Infinite Loop

用户在实际预览中再次确认：仅有“首尾模块循环”仍然不够。Archive Sea 本身也必须在视觉上无边界；任何方向持续拖动或滚动都不能出现最后一排、最后一列、阵列外空地或明显的世界终点。

本轮冻结以下实现约束：

- [x] 不通过无限堆模型来掩盖边界；仍复用有限的轻量档案池，并使用可控 overscan 扩充安全边距。
- [x] 根据实际 Edge 截图继续加厚 overscan：物理循环池由 `9 × 25` 扩充为 `11 × 35`，左右各增加 1 列，上下共增加 10 行缓冲；用户看到的仍是周期映射后的虚拟坐标，不是物理池坐标。
- [x] 池范围统一冻结为 `lane -3..7`、`row -4..30`，所有 Scene 构建、periodic remap 与 track rebase 共用同一 `LOOP_POOL` 常量，避免尺寸漂移。
- [x] 每帧根据当前 `laneTrack / rowTrack`，把每个物理档案映射到距离视野中心最近的周期副本。
- [x] 离开视野最远端的行/列在视野外自动重映射到另一侧；没有真实的“最后一排/最后一列”。
- [x] Focus File 通过 `poolKeyForCell()` 把任意逻辑坐标映射回稳定物理 slot，模块身份仍是临时投影，不与有限物理位置永久绑定。
- [x] 点击循环后的可见档案使用 `virtualLane / virtualRow` 作为导航目标，不会跳回物理池原始坐标。
- [x] Track 只在跨过完整物理池周期后做数值 rebase，且 rebase 与物理池周期完全一致，因此视觉上没有跳变。
- [x] Sleep Drift、Shoulder Wave、LOD 和 Hover 都改用虚拟坐标计算，周期边界不会产生明显相位断裂。
- [x] `root` 不再持续平移到有限阵列边缘；档案卡自身围绕当前逻辑中心循环重排。

自动验收要求：

- [x] `archiveLoop` 纯函数测试覆盖 `±10000` 量级逻辑坐标。
- [x] 任意相隔整数个 pool period 的逻辑 cell 必须复用同一物理 slot。
- [x] 任意远距离 center 下，映射后的 lane 距中心不超过半个 lane pool，row 距中心不超过半个 row pool。
- [x] 周期映射后的 11 个 lane / 35 个 row 在任意远距离 center 下必须连续、无重复、无缺口。
- [x] 新增 overscan 尺寸契约测试，防止后续误回退到旧 `9 × 25` 池。
- [x] Overscan 扩容后 P0 为 `55 / 55` 通过，production build 通过。
- [ ] 真实浏览器连续大量 Wheel / Drag 后，1440×900 四周仍保持完整档案覆盖；不得出现截图中所示的右侧大面积空地边界。
- [ ] 430×844 重复同样的远距离循环验证，底部 Module Index 与 Archive Sea 均不得出现断层。

当前宿主没有可用的 Browser Use / Computer Use 窗口，无法在本轮自动完成最后两项视觉压力截图；因此这两项保留为真实浏览器核验，不以静态测试替代视觉结论。实现侧已经移除有限 root 平移模型，并通过远距离周期覆盖测试证明不会因为逻辑坐标增长而抵达物理网格末端。

---

# 28. V5 第一批实施检查点（2026-09-14）

本节记录已经落地的 V5-A / V5-B / V5-C 第一批实现。它不是最终完成声明，未完成项仍按本文后续阶段继续执行。

## 28.1 匿名物理档案层

- [x] `AnalysisArchiveScene` 不再使用 `moduleAtCell()` / `cellForModule()` 把业务模块永久绑定到物理档案。
- [x] 背景档案统一以 `ANONYMOUS_ARCHIVE` 创建，默认不显示 MARKET / BACKTEST / FINANCIAL 等业务身份。
- [x] 物理档案只保留 `slot:<lane>:<row>` 身份；业务模块身份由页面层临时投影到当前 Focus File。
- [x] FLOW / QUERY 期间高细节 GLB 隐藏，MATCH / FOCUSED / Extraction 时才显示。
- [x] 同一时刻仍只使用一个高细节 Focus GLB，背景继续使用程序化 LOD。

## 28.2 双向循环模块流

- [x] 逻辑模块使用 `wrap(currentIndex + delta, moduleCount)` 实现首尾循环。
- [x] `ArrowLeft / ArrowUp` 统一表示 Previous，`ArrowRight / ArrowDown` 统一表示 Next。
- [x] Wheel / Drag 由物理 Archive Sea 的 row 穿越事件驱动逻辑模块变化。
- [x] 物理 row 循环复用与 11 个逻辑模块环解耦，不再通过固定 cell 找模块。
- [x] 顶部 Module Index 默认折叠；折叠时不再因为 Three.js 物理位置变化执行横向 `scrollIntoView`。
- [x] 直接点击 Module Index 的远距离模块使用环上的最短步数移动，方向相同时保持最近一次导航方向。

## 28.3 Retrieval Illusion

- [x] 新增 `FLOW → QUERY → MATCH → FOCUSED` 视觉状态。
- [x] FLOW 时背景档案保持匿名，目标模块只存在于逻辑状态，不提前写入物理卡片。
- [x] 运动完全停止后才进入 QUERY；快速连续滚动不会对每个中间模块重复播放完整检索动画。
- [x] QUERY HUD 使用细线扫描和 `SEARCHING ARCHIVE…`，未增加新的 WebGL context 或候选评分算法。
- [x] MATCH 后当前 Focus File 才切换到真实业务标签，并允许高细节 GLB 显现。
- [x] MATCH/FOCUS 的工业细节仍复用 V3-G Blender GLB，没有为所有背景档案加载 GLB。

## 28.4 实际行为验收

- [x] 初始 `01 MARKET` 状态可正常进入 `FOCUSED`。
- [x] 实测 `01 → ArrowLeft = 11 OPS`，停止后回到 `FOCUSED · PREVIOUS`。
- [x] 实测 `11 → ArrowRight = 01 MARKET`，停止后回到 `FOCUSED · NEXT`。
- [x] 实测当前为 `05 AI RESEARCH` 时，`ArrowLeft → 04 SIM TRADE`。
- [x] 实测从 05 向后 / 右继续滚动可进入 `06 BULL / BEAR`。
- [x] 桌面 1440×900 截图确认：背景档案不再显示业务标签，仅 Focus File 显示 `BULL / BEAR` 身份和高细节 GLB。
- [x] 430×844 截图确认：仍保留 Archive Sea、Focus File 和底部模块索引，没有退化成普通列表。
- [x] 当前浏览器控制台唯一已知错误仍是本地 Java 后端未启动导致 `/api/market/instruments` 500；V5 第一批未新增前端 JS 运行时错误。

## 28.5 自动回归

- [x] 新增 V5 静态契约：匿名物理档案、`step/settled/flow`、双向 `wrap()`、QUERY/MATCH 视觉状态、禁止真实随机候选算法。
- [x] P0 当前为 `49 / 49` 通过。
- [x] Vite production build 通过。

## 28.6 下一批仍需继续

- [x] V5-D 代码侧：HIGH 档可选 post-processing 已加入轻量 `BokehPass`，BALANCED / MOBILE 保持关闭；仍沿用 GPU probe 与失败回退，不增加 WebGL context。
- [x] V5-D 自动回归：加入 DOF 后 P0 仍为 `49 / 49`，production build 通过，BokehPass 独立 lazy chunk 输出。
- [ ] V5-D 视觉侧：宿主安全检查阻止了本轮自动浏览器视觉复核，因此真实日常 Edge 下的 DOF / 玻璃层次仍需用户预览确认，不标记为完成。
- [ ] V5-D：继续收敛前景裁切与 Fog，让“进入档案海”的摄影效果进一步接近参考视频。
- [x] V5-D：Sleep → Wake 双向接管已做实际等待验收；`01 MARKET / SLEEP_DRIFT` 直接 `ArrowLeft` 可立即醒来并循环到 `11 OPS`，最终为 `AWAKE / FOCUSED · PREVIOUS`，没有先回固定档案。
- [ ] V5-E：继续校准 QUERY / MATCH 在真实日常 Edge 下的可感知时长，避免过快不可见或过慢影响操作。
- [ ] 在用户确认首页整体方向后，才进入上游 PR / merge。

