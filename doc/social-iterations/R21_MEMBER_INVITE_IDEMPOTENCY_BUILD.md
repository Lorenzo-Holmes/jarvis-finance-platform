# R21 构建设计

`SocialService.addMember` 在 OWNER 和目标用户校验后先检查 membership；存在则立即返回 groupView。仅新成员执行 saveMember、activity、audit、achievement。
