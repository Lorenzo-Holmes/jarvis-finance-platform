# R29 需求：删除动态时清理活动引用

问题：社区动态删除后，`user_activity` 中对应 POST_CREATED 仍存在，个人主页会留下指向已不存在动态的记录。

目标：删除动态事务内同步删除该用户对该 POST 的 activity 引用。

验收：post 和对应 activity 同事务删除；其他 activity 不受影响。
