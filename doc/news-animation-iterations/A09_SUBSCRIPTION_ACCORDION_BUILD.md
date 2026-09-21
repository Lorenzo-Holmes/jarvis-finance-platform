# A09 构建设计

- `Transition name="subscription-fold"` 包裹现有 v-show 节点。
- max-height 只做容器过渡，不读取 DOM 高度。
- draft 仍由 refs 保存，不改变订阅行为。
