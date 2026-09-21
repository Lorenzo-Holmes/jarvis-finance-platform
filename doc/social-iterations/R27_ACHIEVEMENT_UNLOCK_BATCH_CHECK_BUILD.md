# R27 构建设计

`evaluate` 返回 key->unlockedAt map；`maybeUnlock` 接收并更新该 map；`catalogState` 直接消费 map。`evaluateSocial` 忽略返回值即可。保留数据库唯一约束作为最终一致性保护。
