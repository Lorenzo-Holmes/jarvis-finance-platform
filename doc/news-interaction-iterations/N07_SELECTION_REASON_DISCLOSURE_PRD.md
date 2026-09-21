# N07 需求：文章“为什么入选”解释

目标：把后端 `selection_reason`、rank/semantic/confirmation 等字段做成按需展开的解释层，不把评分细节永久堆在正文中。

要求：默认折叠；无解释数据时不显示入口；不在前端生成新的判断。
