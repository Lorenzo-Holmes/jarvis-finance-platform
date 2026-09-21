# N20 构建设计

- 根节点声明 `--news-motion-*` 与 `--news-ease`，优先继承 `--ds-motion-*`。
- 关键 article/tab transition 使用 token。
- NewsCenter feed title 使用 sticky translucent material。
- 两组件增加 `prefers-reduced-motion: reduce` 强制降级。
