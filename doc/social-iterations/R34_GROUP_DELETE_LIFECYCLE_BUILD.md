# R34 构建设计

Post Repository 增加 group post id 查询；Activity Repository 增加按 reference 删除；SocialService.deleteGroup 在同一事务中清理 POST/GROUP activity 后删除 group 并审计。Controller/client/UI 增加删除入口与二次确认。
