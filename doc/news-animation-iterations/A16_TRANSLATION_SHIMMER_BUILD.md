# A16 构建设计

- board 根节点绑定 `translating` class。
- title 节点放入 `Transition name="title-swap"`，key 为当前显示标题。
- translating 时 news-copy 叠加一次循环 shimmer，结束即移除。
