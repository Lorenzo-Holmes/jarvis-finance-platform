# JARVIS Analysis OS · Render Fidelity Rebuild V3

版本：V3.0  
日期：2026-09-14  
状态：Implementation Requirement / Render Fidelity Rebuild  
目标分支：`feat/analysis-os-render-fidelity-v3`  
前置基线：`feat/analysis-os-interaction-rebuild-v2`  

---

## 0. 文档目的

本文件冻结 JARVIS Analysis OS 在完成 Interaction Rebuild V2 之后的下一阶段目标：

> 不再只保证“功能上有档案海、可以滚动、可以抽取”，而是把渲染质量、档案本体、镜头语言、材质、光照、解密过渡和 HUD 权重提升到可以与参考终端演示视频同一视觉级别进行比较的状态。

V3 不推翻 V2 已完成的业务结构。以下能力必须保留：

- 11 个业务模块档案；
- 自由二维拖动、滚轮、惯性、吸附；
- Sleep / Wake 档案海；
- Module Index 双向联动；
- Archive → Workspace → Return 主链；
- Global Research Context；
- Workspace Shell；
- Strategy → Backtest handoff；
- 390 / 430 移动端；
- Reduced Motion；
- 登录与真实认证边界；
- 本地 `?preview=1` 仅开发预览入口。

V3 解决的是：**为什么功能已经像，但视觉仍然明显达不到参考演示的质感。**

---

## 1. 当前差距判定

当前 V2 主场景不能视为最终视觉完成，原因冻结如下。

### 1.1 档案本体仍是占位级几何

当前档案核心仍接近：

```text
BoxGeometry
+ 单根 spine
+ marker
+ label plane
```

结果是“文件盒森林”，不是工业设计档案装置。

V3 必须让近景档案具备以下真实结构层级：

- 主承载基板；
- 外框；
- 半透明保护盖；
- 内部双环 / 轨道结构；
- 中央连接带；
- 顶部锁扣 / 横梁；
- 四角紧固点；
- 边缘刻槽；
- 标签槽；
- 状态标记件；
- 背板厚度与倒角感。

不要求复制任何第三方模型资产；JARVIS 必须使用原创结构实现。

### 1.2 当前“浅色”主要依靠雾和低对比

现状：

- 雾偏重；
- 环境光偏平；
- 材质层次弱；
- 接触阴影不足；
- 前中后景分离不够。

V3 的浅色空间必须改为由以下因素共同产生：

- ACES tone mapping；
- 合理曝光；
- 主光 / 侧光 / 轮廓光；
- 软阴影；
- AO / GTAO；
- 低强度 Fog；
- 局部透明和粗糙度差异；
- 前景高对比，中景中等对比，远景雾化。

### 1.3 当前档案海是“数量很多”，不是“摄影构图”

V3 必须重新校准：

- 相机位置；
- 长焦 FOV；
- 消失点；
- 中央构图偏移；
- 档案的 lane / row 间距；
- 可见窗口；
- 前景遮挡关系；
- 远景密度；
- 近景对象比例。

目标：让用户首先感知到“身处一个巨大档案装置空间”，而不是“页面背景放了很多卡片”。

### 1.4 HUD 权重过高

当前中央 `MARKET / 行情 / ACCESS MODULE` 占据过多画面注意力。

V3 冻结原则：

> 3D 档案与镜头占视觉注意力 70%–80%，HUD 只占 20%–30%。

必须：

- 缩小中央模块标题；
- 降低说明文字存在感；
- 顶部 Module Index 进一步轻量化；
- 左下页码减少装饰面积；
- 保留 JARVIS 品牌，但不与空间抢焦点。

### 1.5 抽取过程缺少“解密段”

V2 已有：

```text
Focused
→ Extraction Y Lift
→ Camera Approach
→ Workspace
```

V3 必须升级为：

```text
FOCUSED
→ RELEASE LOCK
→ VERTICAL EXTRACTION
→ CAMERA APPROACH
→ GLASS DECRYPT
→ DOCUMENT REVEAL
→ MODULE HANDOFF
→ WORKSPACE ACTIVE
```

其中 Glass Decrypt / Document Reveal 是 V3 的核心新增视觉段。

---

## 2. 法务与资产边界

### 2.1 可以复用 / 学习

- MIT 代码架构思想；
- Three.js 渲染方法；
- 状态机；
- 镜头运动方法；
- 拖拽 / 惯性 / 无限循环算法；
- 通用 shader / post-processing 技术。

### 2.2 不直接复制

- 第三方 GLB 模型；
- Blender 原模型资产；
- 原品牌图像；
- 原 UI 图片；
- 原声音素材；
- 原世界观文字；
- 原角色 / Logo / 商标。

JARVIS 的档案实体必须是原创金融研究终端设计。

---

## 3. V3 视觉目标

### 3.1 关键词

```text
institutional
archival
precision
glass
warm-neutral
industrial
cinematic
quiet
long-lens
spatial
```

禁止：

```text
neon cyberpunk
AI dashboard card wall
glowing green terminal
game loot-card UI
heavy glassmorphism
excessive bloom
```

### 3.2 主色

- 背景：暖灰白 / 象牙灰；
- 实体：暖白、浅铝、烟灰玻璃；
- 深色结构：石墨灰；
- 聚焦色：低饱和黄铜 / 琥珀；
- 金融涨跌色：只用于业务数据，不用于场景装饰。

---

## 4. 档案实体设计规范

V3 第一版允许使用 Three.js 程序化原创结构，不强制立刻引入 GLB。

原因：

1. 可以先冻结比例、材质和镜头；
2. 不引入第三方资产版权风险；
3. 后续可用原创 Blender GLB 替换，接口保持一致。

### 4.1 结构层级

每个近景档案至少包含：

1. `backPlate`
2. `beveledFrame`
3. `glassCover`
4. `innerRailLeft`
5. `innerRailRight`
6. `innerRingTop`
7. `innerRingBottom`
8. `centerBridge`
9. `topLatch`
10. `labelCarrier`
11. `fourFasteners`
12. `statusMarker`

### 4.2 LOD

禁止 200 个档案全部使用完整高细节结构。

采用三层：

#### LOD0 / Focus

- 完整结构；
- 透明盖板；
- 内部双环；
- 紧固点；
- 标签；
- 阴影；
- 解密动画。

#### LOD1 / Near

- 基板；
- 外框；
- 盖板；
- 标签；
- 部分内部结构。

#### LOD2 / Far

- 低 mesh 数轮廓；
- 统一材质；
- 不创建内部装置；
- 不创建 CanvasTexture 标签或使用共享简化标签。

### 4.3 性能目标

1440×900 桌面：

- 可见档案：≥ 120；
- 完整高细节档案：≤ 9；
- WebGL canvas：始终 1；
- 主场景目标：50–60 FPS；
- 隐藏 Workspace：0 archive RAF；
- 不因进入/退出重复创建 WebGL context。

---

## 5. 材质规范

### 5.1 基板

`MeshStandardMaterial`

- roughness 0.38–0.52；
- metalness 0.05–0.12；
- 暖灰白；
- 接收阴影。

### 5.2 外框

- metalness 0.25–0.42；
- roughness 0.28–0.38；
- 比基板更深；
- 形成清晰轮廓。

### 5.3 玻璃盖

只在 LOD0 / LOD1 使用 `MeshPhysicalMaterial`。

建议：

- transmission：0.55–0.82；
- thickness：0.08–0.16；
- roughness：0.18–0.34；
- ior：1.42–1.50；
- transparent：true；
- opacity 不作为主要玻璃实现方式。

### 5.4 内部件

- 石墨 / 暖银；
- 有清楚的 roughness 差异；
- 聚焦后才增强可见度。

---

## 6. 渲染管线

### 6.1 Renderer

必须配置：

- `SRGBColorSpace`；
- `ACESFilmicToneMapping`；
- `toneMappingExposure`；
- soft shadow map；
- 高质量设备 DPR 上限；
- 移动端自动降低 DPR。

### 6.2 Post Processing

桌面高质量模式：

```text
RenderPass
→ GTAO/SSAO
→ optional Bokeh/DOF
→ OutputPass
```

原则：

- AO 是结构层次的主要增强；
- DOF 只做摄影分层，不做大面积糊屏；
- 不默认加明显 Bloom；
- Reduced Motion 不等于 Reduced Quality。

### 6.3 Quality Profile

建立：

```text
HIGH
BALANCED
MOBILE
```

HIGH：

- DPR ≤ 1.75；
- post AO；
- soft shadow；
- 玻璃 transmission；
- 近景高细节。

BALANCED：

- DPR ≤ 1.4；
- AO 低采样；
- shadow map 降级；
- 部分玻璃简化。

MOBILE：

- DPR ≤ 1.2；
- 无昂贵 post；
- LOD 更积极；
- 保留构图与交互，不牺牲信息架构。

---

## 7. 光照规范

### 7.1 禁止

- AmbientLight 超高强度把全部材质打平；
- 单一 DirectionalLight；
- 纯白曝光把外框吃掉。

### 7.2 推荐灯组

1. 低强度 Hemisphere / Ambient；
2. 大面积主光；
3. 侧后方轮廓光；
4. 低强度暖色 fill；
5. 选中档案可有局部增强光，但不可像游戏掉落物发光。

---

## 8. 相机与档案海构图

### 8.1 浏览态

- 长焦；
- 档案海填充上半屏，不允许顶部巨大空白；
- 前景至少存在明显遮挡；
- 消失点偏离屏幕正中心；
- 当前聚焦档案不必须位于几何中心。

### 8.2 聚焦态

- 卡片浅抬升；
- 镜头不立即明显移动；
- 周边档案对比轻微下降；
- Focus 仍然属于 Archive 状态。

### 8.3 抽取态

- 档案只垂直抬升；
- 不向镜头横飞；
- 镜头负责靠近与构图；
- 背景档案在整个 Extraction 中持续存在。

---

## 9. Glass Decrypt / Document Reveal

### 9.1 时间轴

建议总时长约 900–1300ms，允许 Reduced Motion 缩短。

```text
0–180ms   RELEASE LOCK
120–520ms EXTRACTION
240–720ms CAMERA APPROACH
480–900ms GLASS DECRYPT
650–1050ms DOCUMENT REVEAL
900ms+    WORKSPACE HANDOFF
```

### 9.2 Glass Decrypt

视觉行为：

- 玻璃盖板由磨砂逐渐变清；
- 内部环结构显现；
- 两条细线或扫描边界向中部收束；
- 不使用明显赛博扫描线；
- 不闪白。

### 9.3 Document Reveal

DOM Workspace 在 3D 抽取未完成时不得突然完整出现。

需要：

- 标题先出现；
- 次级信息稍后；
- 主工作区最后完成；
- 3D 卡片和 DOM 内容有 150–300ms 的视觉重叠。

---

## 10. HUD 重构

### 10.1 顶部 Module Index

- 高度更低；
- 字号更小；
- 非当前项透明度降低；
- 当前项仍可识别，但不能成为第一视觉焦点；
- 允许两端 fade mask。

### 10.2 中央 Callout

必须从当前的大标题 Hero 改为“附着信息”：

- 英文模块名缩小；
- 中文名作为次级；
- 说明只保留一行；
- capabilities 作为极弱 microtext；
- `ACCESS MODULE` 变为细线 action。

### 10.3 左下编号

- 缩小；
- 保留状态感；
- 不使用大面积空白版式抢镜。

---

## 11. Sleep / Wake 与 V3 的关系

Sleep 仍使用 V2 的逻辑状态机，但 V3 视觉升级：

- 波浪主要表现为结构高低差与反射变化；
- 镜头漂移更缓慢；
- 玻璃反射随角度轻微变化；
- 不通过大幅雾变化制造“睡眠”。

Wake：

- AO / 光照状态不跳变；
- sleep offset 继续从当前画面被用户接管；
- 目标卡片迅速恢复结构清晰度。

---

## 12. 开场动画

V3 后半阶段加入，不阻塞第一阶段渲染升级。

建议：

```text
JARVIS SYSTEM
→ identity line
→ access scan
→ module archive online
→ camera reveal archive sea
```

禁止复制第三方 Logo 动画或文字。

---

## 13. 声音

V3 第一阶段不要求声音。

后续原创声音类别：

- archive hover；
- archive lock release；
- extraction；
- decrypt glass；
- workspace online；
- return archive。

---

## 14. 实施阶段

### Phase V3-A · Render Foundation

- 新建 render quality controller；
- ACES / exposure / shadow；
- AO post-processing；
- 重做光照；
- 降低 Fog。

### Phase V3-B · Archive Assembly

- 原创高细节档案结构；
- LOD0/1/2；
- 玻璃；
- 内部双环；
- 紧固点 / 框架；
- 标签槽。

### Phase V3-C · Camera Composition

- 重校 FOV；
- 重校 camera base/detail；
- 重校 lane/row spacing；
- 前中后景分层；
- HUD 降权。

### Phase V3-D · Decryption Transition

- lock release；
- glass decrypt；
- internal reveal；
- document reveal；
- Workspace handoff overlapping。

### Phase V3-E · Quality / Performance

- HIGH/BALANCED/MOBILE；
- FPS；
- RAF；
- context；
- 390/430；
- Reduced Motion。

### Phase V3-F · Opening / Audio

- 原创开场；
- 原创音效；
- 最终演示节奏。

---

## 15. V3-A/B 第一批构建范围

本轮立即实现：

1. ACES Tone Mapping；
2. exposure 校准；
3. soft shadow；
4. GTAO/SSAO 桌面路径；
5. 更克制 Fog；
6. 新灯组；
7. 原创多层档案结构；
8. 焦点档案内部双环；
9. 玻璃盖板；
10. far LOD 保持性能；
11. HUD 视觉降权；
12. extraction 期间玻璃逐渐解密；
13. V2 全部测试不回退。

---

## 16. 第一批验收标准

### 16.1 静态画面

- [ ] 近景档案不再可被概括为一个 Box + Label。
- [ ] 可看见明确外框 / 玻璃 / 内部结构 / 紧固件。
- [ ] 前景边缘清晰，中景层次明确，远景雾化。
- [ ] 不再“整屏白蒙蒙”。
- [ ] 中央 HUD 不再是第一视觉焦点。

### 16.2 动态

- [ ] Focus 卡片内部结构比未选中档案更清晰。
- [ ] Extraction 时档案继续只沿 Y 轴抬升。
- [ ] 相机承担构图靠近。
- [ ] 玻璃在 extraction 后半段产生 decrypt/reveal 变化。
- [ ] Sleep/Wake 行为不退化。

### 16.3 性能

- [ ] 1440px 单 canvas。
- [ ] Workspace 隐藏 Archive RAF=0。
- [ ] 反复进入/返回不增加 WebGL context。
- [ ] 390/430 无 document 横向溢出。
- [ ] Reduced Motion 不进入完整自主巡航。

### 16.4 工程

- [ ] `npm run test:p0` 全通过。
- [ ] `npm run build` 通过。
- [ ] `.playwright-cli/` 不提交。
- [ ] 本地 `?preview=1` 只在 DEV + localhost 生效。

---

## 17. Master Definition of Done

Render Fidelity Rebuild V3 完成必须同时满足：

1. 用户截图中不再出现“白色盒子森林 + 大 HUD”的第一印象。
2. 档案本体具备可识别的原创工业结构。
3. 玻璃、框架、内部件在材质上可以区分。
4. 浏览态有明确摄影构图和纵深。
5. Focus、Extraction、Decrypt、Workspace 是连续运动链。
6. HUD 是辅助层，不是主视觉。
7. Sleep / Wake 与新的材质/光照协调。
8. 移动端保留交互而非全局缩放桌面版。
9. 不复制第三方非代码模型/图像/声音资产。
10. 用户最终视觉确认后，才允许进入上游合并流程。

---

## 18. 当前结论

V2 解决：

> “系统怎么工作。”

V3 解决：

> “为什么它看起来、运动起来真正像一个高完成度空间终端，而不是一个带 Three.js 背景的网页。”

后续所有视觉修改必须以本文件为基线，不允许再退回只调颜色、透明度或卡片 CSS 的方案。

---

## 19. 2026-09-14 · V3 第一批实现检查点

当前分支：`feat/analysis-os-render-fidelity-v3`。

### 19.1 已实现

- [x] 新增 V3 独立需求文档并与 V2 Master Spec 分离。
- [x] 新增 `archiveAssembly.js`，档案从单 Box 升级为原创多层装配体。
- [x] 档案结构包含主基板、内衬、四边框、玻璃盖、双内轨、双环、中央桥、顶部锁扣、四角紧固点、标签托架、状态件与 decrypt guide。
- [x] 新增 HIGH / BALANCED / MOBILE 渲染质量档位。
- [x] Renderer 使用 ACES Filmic tone mapping 与 sRGB 输出。
- [x] 桌面启用方向光阴影，移动端关闭昂贵阴影路径。
- [x] Ambient 强度显著降低，改为 Ambient + Hemisphere + Key + Fill + Rim 的分层灯组。
- [x] Fog 从主要画面塑形工具降为远景衰减工具。
- [x] 档案海 lane / row spacing、相机高度、FOV、detail camera 已重新校准。
- [x] 近景档案才显示玻璃 / 内部结构；远景只保留主要轮廓，形成运行时 LOD。
- [x] Focused 档案可切换独立玻璃材质，Extraction 后半段降低 roughness、提高 transmission。
- [x] Extraction 新增 Release Lock / Vertical Extraction / Camera Approach / Glass Decrypt / Document Reveal 五阶段 HUD。
- [x] 解密段新增左右收束的结构线，不依赖第三方图像或模型资产。
- [x] Document Reveal 在 Extraction 后段先展开轻量 Workspace Surface，并保留约 180ms 完整揭示后再交给真实业务 Workspace。
- [x] 抽取总时长由 820ms 延长至约 1080ms，给镜头和材质变化留出阅读时间。
- [x] 主 HUD、Module Index、左下编号、Powered by JARVIS 已降权，3D 档案重新成为第一视觉层。
- [x] `?preview=1` 本地开发入口继续保留，未修改生产认证逻辑。
- [x] 新增 `analysis-render-fidelity.test.mjs` 冻结 V3 核心渲染契约。
- [x] 未启用的 AO/post-processing 改为动态 import，默认 direct renderer 不再承担未使用的首屏代码成本。

### 19.2 当前兼容性决策

SSA0 post-processing 管线代码已建立，但默认暂时保持 compatibility gate：

- HIGH / BALANCED 标记为 `postCandidate`；
- 默认主路径先使用 ACES + shadow + layered geometry；
- 原因是自动化 Chromium / ANGLE 环境中 SSAO/OutputPass 出现 shader validate 兼容性问题，会导致整个画面失效；
- 在实际浏览器完成 GPU 白名单 / fallback 逻辑前，不允许为了 AO 牺牲主场景可用性。

因此当前 V3-A 结论是：

> AO 管线已经具备实现入口，但生产默认以稳定直接渲染为准；后续必须实现“post-processing 失败自动回退 direct renderer”后再默认启用。

### 19.3 当前视觉验收结果

本地 1440×900 自动截图确认：

- [x] 档案海不再是纯白低对比盒子；边框、深色内轨和近景结构可以区分。
- [x] 前景 / 中景 / 远景形成明显密度和遮挡关系。
- [x] Focused 档案有外框、锁扣、紧固点和内部环结构。
- [x] Extraction 过程中实体真实从档案海抬升，内部件可见，背景档案保持存在。
- [x] HUD 比 V2 明显减小，不再完全压过档案实体。
- [x] 1440×900 HIGH 直接渲染路径实测约 61 FPS；当前约 45 个 Near LOD、1 个 Focus LOD、约 900 draw calls。
- [x] 430×844 MOBILE 路径无 document 横向溢出，单 canvas，约 456 draw calls。
- [x] Archive → Workspace 后隐藏档案场 500ms 内 renderedFrames 增量为 0；返回后恢复渲染，canvasCount 始终为 1。
- [x] 当前前端 P0 回归 44 / 44 通过，Vite production build 通过。
- [x] 默认未启用 AO 的代码已拆为独立 lazy chunks，Analysis OS 主 chunk 约 541 KB，而不是把兼容候选管线全部塞进首屏。

### 19.4 下一批

- [ ] 对实际用户 Edge 完成 V3 GPU / shadow / glass 视觉确认。
- [ ] 实现 post-processing 自动探测与失败回退，再评估默认 SSAO。
- [ ] 继续精修 Focus 档案本体比例、内部双环与玻璃边缘。
- [x] 增加 Document Reveal 与 Workspace DOM 交接前的完整揭示保持时间。
- [ ] 补原创开场序列。
- [ ] 补原创声音系统。
- [ ] 最终与参考视频按关键帧进行构图 / 节奏对照，而不是只比较单张截图。

---

## 20. 2026-09-14 · V3 第二批实现检查点

### 20.1 Post-processing 安全探测

- [x] HIGH / BALANCED 不再直接盲开 SSAO，而是在 direct renderer 稳定运行后异步探测。
- [x] 探测前清空历史 WebGL error，探测后读取当前 context error。
- [x] 探测失败时销毁 composer，并立即恢复 direct renderer。
- [x] 同一会话内 GPU 探测失败后锁定 direct renderer，不循环轰炸 shader。
- [x] MOBILE / Reduced Motion 不启用昂贵 post-processing。
- [x] HIGH → MOBILE → HIGH 实测可从 `enabled → direct → enabled` 正常恢复，canvasCount 始终为 1。
- [x] 当前本地 Edge/Chromium 验收环境自动探测成功，post-processing 处于 enabled，约 60 FPS。

### 20.2 Archive Assembly 第二轮比例精修

- [x] 档案主体由 3.38×4.96 调整为更窄、更薄的 3.16×4.62 比例。
- [x] 玻璃、标签槽、内部双环、轨道与锁扣同步缩放，减少“厚白文件夹”观感。
- [x] Focus 档案新增独立玻璃 gasket / 压条，仅 Focus LOD 渲染，不增加远景成本。
- [x] lane / row spacing 再次收紧，保持档案海密度，同时控制遮挡。
- [x] Focus 浏览态抬升由 0.42 调整为 0.62；附近档案产生局部 clearance 下沉，当前档案更易识别。
- [x] 仍保持 Extraction 只沿 Y 轴继续抬升，没有向相机飞卡。

### 20.3 原创 Opening Sequence

- [x] 旧 900ms 单层 Boot Overlay 已替换为多阶段原创开场。
- [x] 阶段：`SYSTEM WAKE → IDENTITY RESOLVED → PERMISSION SCAN → MODULE ARCHIVE ONLINE → SYSTEM READY`。
- [x] 开场包含扫描网格、系统轨道标记、权限阶段条、进度线与 JARVIS 身份信息。
- [x] READY 阶段不瞬切；启动层渐隐，由下面的实时档案海接管画面。
- [x] 430px 开场实测无横向溢出，标记、标题、进度和权限条均在视口内。
- [x] Reduced Motion 仍可快速跳过长开场。

### 20.4 原创声音系统

- [x] 新增 `useArchiveAudio.js`，全部声音由 WebAudio Oscillator / Noise Buffer 实时合成。
- [x] 未引入 mp3 / wav / ogg 或第三方游戏音效资产。
- [x] 声音默认关闭，必须由用户主动点击 SOUND 开启，遵守浏览器自动播放限制。
- [x] 已覆盖 wake / focus / extract / decrypt / reveal / return 六类反馈。
- [x] SOUND 偏好保存在本地 localStorage，可随本地预览会话恢复。

### 20.5 第二批实际验收

- [x] 1440×900 开场 Permission 关键帧已截图检查。
- [x] 430×844 开场 Permission 关键帧已截图检查，document 宽度等于 viewport。
- [x] 最新主档案海截图中 HUD 已移至右侧，焦点档案与 HUD 不再完全重叠。
- [x] 最新 Glass Decrypt 关键帧中玻璃边、双环、压条和 decrypt guide 均保持可辨识。
- [x] 当前 post-processing enabled 路径无新增 WebGL/shader console error。
- [x] 当前控制台剩余错误仅为本地 Java 后端未启动导致 `/api/market/instruments` 500。
- [x] SOUND 开关实测可切换 `aria-pressed=false → true` 并写入本地偏好。

### 20.6 仍未完成

- [ ] 在用户真实日常 Edge 窗口中做最终 GPU / 色彩 / 玻璃视觉确认，而不只依赖自动验收会话。
- [ ] 与参考视频选定 4–6 个关键帧逐项对照相机高度、档案占屏比、景深、HUD 边界和切换节奏。
- [ ] 如最终对照仍存在明显模型差距，再决定是否制作独立 Blender/GLB 原创档案资产；当前程序化装配体不得直接替换为第三方模型。
- [ ] 完成最终用户视觉确认后，才进入上游 PR / merge 流程。
