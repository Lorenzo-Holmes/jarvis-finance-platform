# A18 构建设计

- `feedPanelRef` + `feedProgress`。
- capture scroll + resize 计算 panel 相对 viewport 的进度。
- 通过 CSS 变量 `--feed-progress` 驱动 sticky header `::after` scaleX。
