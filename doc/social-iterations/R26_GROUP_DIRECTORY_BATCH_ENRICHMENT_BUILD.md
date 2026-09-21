# R26 构建设计

Member/Post Repository 增加 groupIds 聚合 count；Member Repository 增加 `findByGroupIdInAndUserId`。SocialService 新增 `groupPageView`，一次收集 groupIds/ownerIds 后构造 lookup map，目录不再调用 detail-oriented `groupView`。
