-- 用户定时任务：执行历史
--
-- 每触发一次落一行，是"执行状态可追踪 + 失败具备错误记录"这两条验收的载体。
--
-- 关键设计：idempotency_key 上的唯一约束是**防重复执行的核心机制**。
-- 到点触发时不先执行，而是先按 <taskId>:<scheduledAt 的 epochSecond>[:MANUAL]
-- 插一行 RUNNING：插得进去才继续执行，唯一约束冲突就说明这个计划时刻已被处理过，
-- 直接记一行 SKIPPED 返回。这样一次覆盖三种重复场景：
--   ① 同一时刻被触发多次（调度重建、时钟回拨）；
--   ② 进程重启后按库重建调度，把已经跑过的时刻又算了一遍；
--   ③ 用户连点"立即执行"。
-- 为什么不走 SELECT ... FOR UPDATE SKIP LOCKED：本地 H2 不支持该语法，
-- 依赖它的方案在本机根本测不出真问题；唯一约束在 H2 与 PostgreSQL 上行为一致。
-- 生产是单机单实例（deploy/systemd/jarvis-java.service），不需要选主。
--
-- 其余：
--   * artifacts_json 只放**引用**（站内链接/ID），产物本体另存，避免本表膨胀。
--   * artifacts_json 用 VARCHAR 而不是 TEXT，原因同 V9（CLOB 与 validate 冲突）。
--   * error_message 只存截断后的摘要，完整堆栈留在应用日志里。
--   * 本表没有 version 列：run 记录一旦落定就不再并发编辑，乐观锁在这里没有意义。
CREATE TABLE scheduled_task_run (
    id BIGSERIAL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    trigger_type VARCHAR(16) NOT NULL,
    scheduled_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP NOT NULL,
    finished_at TIMESTAMP,
    status VARCHAR(16) NOT NULL,
    duration_ms BIGINT,
    result_summary VARCHAR(1000),
    artifacts_json VARCHAR(4000),
    error_type VARCHAR(120),
    error_message VARCHAR(1000),
    idempotency_key VARCHAR(128) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_task_run_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_task_run_task FOREIGN KEY (task_id) REFERENCES scheduled_task(id) ON DELETE CASCADE
);

-- 执行历史列表：按任务取最近 N 条，分页。
CREATE INDEX idx_task_run_task_created ON scheduled_task_run(task_id, created_at DESC);

-- 僵尸记录清理：进程被 kill 时会留下 RUNNING 且 finished_at 为空的记录，
-- 启动时需要按这条索引把它们改判为 TIMEOUT。
CREATE INDEX idx_task_run_status_started ON scheduled_task_run(status, started_at);
