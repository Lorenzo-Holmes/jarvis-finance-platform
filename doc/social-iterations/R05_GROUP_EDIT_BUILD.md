# R05 构建设计

新增 `PATCH /api/social/groups/{groupId}`，复用 GroupRequest。SocialService 校验 owner 后保存并审计。前端组主显示编辑模式。
