# R33 构建设计

SocialService 增加统一 `validateUserText(field,value)`，在 profile/group/post/message 写路径调用。使用 Character.isISOControl，白名单仅 `\n/\r/\t`。
