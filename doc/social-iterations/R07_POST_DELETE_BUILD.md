# R07 构建设计

新增 `DELETE /api/social/posts/{postId}`。Service 读取帖子并校验 authorUserId，删除后写审计。postView 增加 `mine`，前端仅对自己的动态显示删除按钮。
