# N09 构建设计

- 保存一份最近成功加载/保存的 source/topic snapshot。
- `subscriptionDirty` 对排序后的数组做稳定比较。
- 保存按钮在无修改时禁用，状态文案同步反馈。
