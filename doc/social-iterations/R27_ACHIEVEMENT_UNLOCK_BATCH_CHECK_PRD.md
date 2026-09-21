# R27 需求：成就解锁批量判定

问题：每次成就评估会对 11 枚勋章逐一执行 `existsByUserIdAndAchievementKey`，随后 overview 又重新读取一次全部成就。

目标：一次读取当前已解锁集合，在内存中完成本轮全部判定，并直接复用到 overview 输出。

验收：一次 overview 只需一次已解锁列表查询；不再逐枚举调用 exists；新解锁仍立即进入返回状态。
