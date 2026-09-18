# JARVIS Workspace Visual System

`WorkspaceSystem.css` 是登录后 Financial Research OS 的共享视觉层。页面组件保留自己的业务布局；跨页面一致的材质、动效、焦点与选中状态统一放在这里。

## Token 分层

- `--ds-motion-*`：Press / State / Surface / Layout 四档时长。
- `--ds-ease`：主要空间过渡曲线。
- `--ds-radius-*`：Control / Selection / Context Capsule 圆角。
- `--ds-focus-shadow`：键盘焦点反馈。
- `--ds-selection-*`：Liquid Selection Lens。
- `--module-aura` / `--context-accent`：不同工作区的低对比环境色场。

## 材质原则

Glass 只用于工具层：Popover、Command Palette、Inspector、Floating Toolbar、Graph Node。主 Canvas、K 线背景、Research Document 和长表格保持实底或透明内容面。

## 动效原则

优先动画 `transform` 与 `opacity`；Selection Lens 可动画 `width`。避免大面积 `filter`、gradient position 和多层 glow。所有关键动效必须提供 `prefers-reduced-motion` 降级。

## Selected / Focus

同一控件只使用一套主 Selected 信号。当前导航采用共享 Selection Lens；Focus 采用统一 ring，不通过布局偏移表达状态。

## 修改流程

1. 先修改共享 token 或 Workspace System，再决定是否需要页面局部覆盖。
2. 不新增 V8/V9 式尾部覆盖区；若规则属于共享语言，应进入本文件。
3. 修改后运行 `frontend` P0 测试和 `e2e` Visual Regression。
4. 有意改变视觉时使用 `npm run test:visual:update` 更新基线，并在 PR 中说明变化原因。
