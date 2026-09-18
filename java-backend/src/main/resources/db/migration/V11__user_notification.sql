-- 站内通知（PRD V1.2 增量需求 3「用户定时任务与任务管理系统」→ 通知机制）
--
-- 为什么需要这张表：PRD 明确要求「Web 站内通知」与「高风险事件优先提醒」，
-- 而全仓此前**没有任何通知基础设施**（无表、无实体、无接口、无前端入口）。
-- 定时任务失败、连续失败被自动暂停、风险检测命中这些事实当前只落在
-- scheduled_task_run 里，用户不主动去看就永远不知道。
--
-- 几个刻意的选择：
--   * level 单列出来而不只靠 type 推：PRD 要求「高风险事件优先提醒」，
--     列表必须能按等级排序（RISK > WARN > INFO），等级是排序依据、类型是分类依据。
--   * body 用 VARCHAR(1000)、title 用 VARCHAR(120) 且都截断后写入：通知是"提醒"，
--     详情留在 scheduled_task_run 里；这类表增长快，不能让它变成第二份执行历史。
--   * link_kind + link_ref 只存**引用**（指向任务/执行记录），不存正文 ——
--     同一份结果两处存储必然两处不一致。
--   * dedup_key 上**刻意不加唯一约束**：去重语义是"同一任务同一类型的事件在
--     N 分钟窗口内合并为一条并累加 repeat_count"，窗口之外应当允许再写一条。
--     加了唯一约束就变成"永远只留一条"，会把"上周坏过一次、这周又坏了"抹掉。
--     改为普通索引，供窗口内查找。
--   * repeat_count + last_seen_at：合并时不改 created_at（保留首次发生时间），
--     另记最近一次发生时间与次数 —— 列表按 last_seen_at 倒序，用户能看出
--     "这个故障还在持续发生"，而不是被一条陈旧的记录淹没。
CREATE TABLE user_notification (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    level VARCHAR(16) NOT NULL,
    title VARCHAR(120) NOT NULL,
    body VARCHAR(1000),
    link_kind VARCHAR(24),
    link_ref BIGINT,
    dedup_key VARCHAR(128) NOT NULL,
    repeat_count INTEGER NOT NULL DEFAULT 1,
    read_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    last_seen_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_notification_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 列表主查询：按用户 + 最近一次发生时间倒序。
CREATE INDEX idx_user_notification_user_seen ON user_notification(user_id, last_seen_at DESC);

-- 未读数与「只看未读」过滤。
CREATE INDEX idx_user_notification_user_unread ON user_notification(user_id, read_at);

-- 去重窗口内查找（非唯一，见上面的说明）。
CREATE INDEX idx_user_notification_dedup ON user_notification(user_id, dedup_key, last_seen_at);
