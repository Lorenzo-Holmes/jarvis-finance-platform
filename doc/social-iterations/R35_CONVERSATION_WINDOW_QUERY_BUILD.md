# R35 构建设计

DirectMessageRepository 使用 PostgreSQL/H2 均支持的 `ROW_NUMBER() OVER(PARTITION BY CASE...)` native query 取得每个 partner 的最新消息，并增加 JPQL unread count 聚合。SocialService.conversations 只处理“每会话一行”。新增 H2 PostgreSQL-mode 仓储测试验证窗口查询真实 SQL 行为。
