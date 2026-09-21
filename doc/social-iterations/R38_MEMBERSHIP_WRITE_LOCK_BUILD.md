# R38 构建设计

CommunityGroupRepository 新增 `@Lock(PESSIMISTIC_WRITE)` 的 `findByIdForMembershipUpdate`。SocialService 增加 `requireGroupForMembershipUpdate`，仅四类 membership write 使用，避免扩大锁范围到目录/Feed 读取。
