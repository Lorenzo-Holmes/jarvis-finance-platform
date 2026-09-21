# R32 构建设计

新增 `requireActiveUser` helper，基于 requireUser 后检查 enabled。仅用于 addMember/sendMessage 这类“建立新关系”写操作，不用于历史渲染，避免禁用账号导致旧记录无法查看。
