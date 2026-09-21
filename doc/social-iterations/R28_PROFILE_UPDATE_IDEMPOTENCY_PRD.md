# R28 需求：个人资料更新幂等

问题：用户反复点击保存相同资料会重复写 PROFILE_UPDATED 动态、审计记录并触发成就评估。

目标：归一化后的资料与隐私设置完全相同时视为 no-op，不写数据库、不产生动态/审计。

验收：相同值重复保存返回当前资料；不调用 user save、activity save、audit record、social achievement evaluate。
