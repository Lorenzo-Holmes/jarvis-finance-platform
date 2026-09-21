# R06 构建设计

新增 `DELETE /api/social/groups/{groupId}/members/{userId}`。Service 校验 owner 与目标成员角色后删除并写审计。前端展示成员列表与 OWNER 专属移除按钮。
