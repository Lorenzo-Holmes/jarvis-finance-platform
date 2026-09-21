# R30 构建设计

DirectMessageRepository `findThread` 改返回 `Page<DirectMessage>`，底层仍按 createdAt DESC 分页。Service 将当前页 content reverse 后使用统一 page envelope 返回。Controller/client 增加 page/size。CommunityPage 保存 `threadPage/threadHasMore` 并前插 older page。
