# R25 构建设计

新增 `postPageView`：收集 page 中 authorUserId/groupId，分别 `findAllById`，构造 lookup map；增加带 lookup 的 `postView` 重载。Feed/groupPosts 改为调用该 helper，单条创建响应沿用原逻辑。
