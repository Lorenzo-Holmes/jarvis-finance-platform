# N17 构建设计

- `feedQuery` + `filteredArticles` computed。
- 搜索字段：中英文标题、summary、source/source_id、tags、selection_reason。
- 无匹配时显示独立 empty state，原 articles 不被修改。
