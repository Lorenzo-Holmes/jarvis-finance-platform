# R22 构建设计

在 `SocialService.createPost` 中归一化 referenceType/referenceId，并用 XOR 检查成对完整性。异常使用 `ResponseStatusException(BAD_REQUEST)`。新增 service 单测。
