# R29 构建设计

UserActivityRepository 增加 `deleteByUserIdAndReferenceTypeAndReferenceId`。`deletePost` 在 author 校验成功后执行 activity cleanup 与 post delete，并保持原审计记录。
