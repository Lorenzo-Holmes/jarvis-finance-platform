-- 用户定时任务：任务定义
--
-- 为什么需要这张表：Java 侧现有 8 处 @Scheduled 全是**编译期固定**的注解式
-- （MarketDataService / JdGoldService / SimRiskService / SimOrderService），
-- Spring 没有反注册注解的 API，所以"用户创建即生效、暂停即停止"做不到。
-- 只能把任务定义落库，启动时按库重建 TaskScheduler 的注册。这张表就是那份定义。
--
-- 几个刻意的选择：
--   * params_json 用 VARCHAR 而不是 TEXT：TEXT 在 H2 里映射成 CLOB，与 Hibernate
--     对 String 的默认映射（VARCHAR）不一致，生产 ddl-auto=validate 会直接启动失败。
--     V8__research_tasks.sql 的注释里也记了同一个坑。
--   * status 存枚举名（实体配 @Enumerated(STRING)）而不是序号：以后加状态或调整顺序
--     都不会把历史数据读错。
--   * 删除走软删（status='DELETED'）而不是物理删除：执行历史挂在 scheduled_task_run
--     上，物理删会因 ON DELETE CASCADE 把历史一起带走，而验收要求"记录执行历史与异常"。
--   * timezone 列保留但本期只允许 Asia/Shanghai：列在，将来要多时区不必改表。
CREATE TABLE scheduled_task (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    cron_expr VARCHAR(64) NOT NULL,
    timezone VARCHAR(40) NOT NULL DEFAULT 'Asia/Shanghai',
    params_json VARCHAR(4000) NOT NULL DEFAULT '{}',
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    next_run_at TIMESTAMP,
    last_run_at TIMESTAMP,
    last_run_status VARCHAR(16),
    consecutive_failures INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uk_scheduled_task_user_name UNIQUE (user_id, name),
    CONSTRAINT fk_scheduled_task_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

-- 列表页按用户 + 状态过滤（默认过滤掉 DELETED）。
CREATE INDEX idx_scheduled_task_user_status ON scheduled_task(user_id, status);

-- 启动时重建调度、以及到期任务扫描都走这条：只需扫描 ACTIVE 且到点的行。
CREATE INDEX idx_scheduled_task_due ON scheduled_task(status, next_run_at);
