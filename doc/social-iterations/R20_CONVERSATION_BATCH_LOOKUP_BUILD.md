# R20 构建设计

SocialService.conversations 在完成 latest/unread 聚合后，以 `latest.keySet()` 调用 `userRepository.findAllById`，构建 id->User map 后生成 DTO。新增单测验证不再逐条调用 findById。
