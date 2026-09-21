# R08 构建设计

复用现有后端分页。CommunityPage 保存 `feedPage/feedHasMore`，`loadFeed(reset)` 根据 `totalPages` 决定是否继续加载。
