# R02 构建设计

Repository 增加未读计数方法，SocialService/Controller 暴露 `/api/social/messages/unread-count`。前端 client 接入，CommunityPage 在初始化、会话刷新和发送后同步计数。
