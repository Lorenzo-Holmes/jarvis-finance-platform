# R34 需求：研究小组删除生命周期

问题：研究小组只能创建/编辑，无法由 OWNER 关闭并删除；同时 group/post activity 没有外键，单靠数据库级联会留下悬空动态。

目标：仅 OWNER 可删除小组；成员关系和帖子使用既有 FK cascade；GROUP 与组内 POST 的 user_activity 引用显式清理。

验收：非 OWNER 返回 403；OWNER 删除后组、成员、帖子消失；关联 activity 不再保留。
