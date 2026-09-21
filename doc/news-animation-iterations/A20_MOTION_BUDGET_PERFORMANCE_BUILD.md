# A20 构建设计

- article/news item 使用 `contain: paint` 缩小重绘范围。
- 仅 active refreshing/translating/transition 节点使用 `will-change`。
- 连续动画严格受 `.active` / `.refreshing` / `.translating` 状态门控。
- contract test 固定 motion budget 与 reduced-motion 约束。
