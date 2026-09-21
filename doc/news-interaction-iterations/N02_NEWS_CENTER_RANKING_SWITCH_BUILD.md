# N02 构建设计

- `rankingMode = ref('smart')`。
- `loadDigest` 透传 ranking。
- segmented control 使用 `aria-pressed`。
- 切换时调用 `changeRanking` 并保留当前订阅。
