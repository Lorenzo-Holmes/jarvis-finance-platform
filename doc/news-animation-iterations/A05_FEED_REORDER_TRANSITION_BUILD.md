# A05 构建设计

- 文章节点包入 `TransitionGroup name="article-flow"`。
- enter/leave 使用短距离 fade；move 使用 layout token。
- leaving item 绝对定位，避免相邻卡片二次跳动。
