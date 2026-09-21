# R31 构建设计

UserRepository 增加 enabled=true 的分页派生查询，SocialService.searchUsers 仅在社交发现路径使用新方法。原管理端查询方法保持不变。
