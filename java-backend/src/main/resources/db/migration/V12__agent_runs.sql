-- Financial Agent：运行元数据与可观察事件的持久化
--
-- 运行中的 SSE 订阅器仍在应用内存中，数据库是历史、重放与审计的唯一事实来源。
-- 使用 VARCHAR 而不是 PostgreSQL 专有 JSONB，确保现有 H2 + ddl-auto=validate 契约
-- 与生产 PostgreSQL 的迁移结构一致；payload_json 由 ObjectMapper 序列化。
CREATE TABLE agent_run (
    run_id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    question VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    event_count INTEGER NOT NULL DEFAULT 0,
    last_sequence BIGINT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    CONSTRAINT fk_agent_run_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE agent_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL,
    step_id VARCHAR(64) NOT NULL,
    sequence BIGINT NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    status VARCHAR(16) NOT NULL,
    title VARCHAR(200),
    tool VARCHAR(100),
    input_summary VARCHAR(2000),
    output_summary VARCHAR(2000),
    payload_json VARCHAR(40000),
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    duration_ms BIGINT,
    error_code VARCHAR(64),
    CONSTRAINT fk_agent_event_run FOREIGN KEY (run_id) REFERENCES agent_run(run_id) ON DELETE CASCADE,
    CONSTRAINT uk_agent_event_sequence UNIQUE (run_id, sequence)
);

CREATE INDEX idx_agent_run_user_created ON agent_run(user_id, created_at);
CREATE INDEX idx_agent_run_status ON agent_run(status);
CREATE INDEX idx_agent_event_run_sequence ON agent_event(run_id, sequence);
