# R38 需求：小组成员变更并发串行化

问题：join/invite/remove/leave 都是“先检查 membership，再写入”。同一小组并发请求可能同时通过检查并撞唯一约束，或出现检查后状态已变化的 TOCTOU。

目标：成员关系写操作先对 community_group 行取得悲观写锁，使同组成员变更串行化。

验收：join/add/remove/leave 统一走 locked group read；普通只读详情不加锁；业务返回语义不变。
