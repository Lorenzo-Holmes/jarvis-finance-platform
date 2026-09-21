# N14 构建设计

- `preferenceRequestSeq`、`digestRequestSeq` 分离。
- 请求发出时捕获 seq，await 后比较；不一致立即返回。
- `onBeforeUnmount` 递增 seq，防卸载后写状态。
