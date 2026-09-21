# R09 构建设计

CommunityPage 增加 `groupPostPage/groupPostHasMore`，拆出 `loadGroupPosts(groupId, reset)`。小组详情加载与发布后统一走该函数。
