# N03 构建设计

- MarketNewsBoard 增加 `rankingMode`。
- `load()` 透传 ranking。
- 切换模式时重置分页并重新拉取。
- 使用 compact segmented control，不挤占主要刷新操作。
