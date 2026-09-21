# R37 构建设计

SocialService 增加 `normalizeSearchQuery`，searchUsers/groups 共用。统一边界 80，服务层校验保证未来其它 controller 复用时仍安全。
