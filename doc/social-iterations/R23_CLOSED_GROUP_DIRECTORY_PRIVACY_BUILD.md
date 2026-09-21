# R23 构建设计

CommunityGroupRepository 新增 viewer-aware JPQL：`visibility=OPEN OR groupId in memberships(viewer)`。搜索版本在同一可见性条件下增加 case-insensitive name LIKE。SocialService.groups 统一使用 viewer-aware 查询。
