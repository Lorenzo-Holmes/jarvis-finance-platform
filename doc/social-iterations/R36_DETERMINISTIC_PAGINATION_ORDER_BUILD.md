# R36 构建设计

Feed/group posts/message thread/activity/group directory 的查询统一追加 `id DESC`；客户端线程仍对每页 reverse，因此展示依旧为页内时间正序。增加同时间消息仓储测试。
