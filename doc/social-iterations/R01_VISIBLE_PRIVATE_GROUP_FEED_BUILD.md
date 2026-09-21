# R01 构建设计

将 `CommunityPostRepository.findPublicFeed` 改为带 `userId` 的 `findVisibleFeed`，JPQL 增加成员子查询。`SocialService.feed` 传入当前用户 ID。补单测固定服务层调用契约。
