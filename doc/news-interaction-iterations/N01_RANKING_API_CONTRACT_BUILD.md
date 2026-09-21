# N01 构建设计

- `api.newsDaily(limit, force, ranking='smart')`。
- GET 参数继续包含 `refresh=true`、`force`，新增 `ranking`。
- 更新 frontend contract test，冻结默认值与参数透传。
