-- AI Research Core：研究任务与研究报告
--
-- 一条研究任务 = 一次"带上下文的研究请求"，报告与上下文都随任务一起存下来，
-- 于是"历史研究记录"不需要第二张表：列表看元数据，详情看 report_json。
--
-- 几个刻意的选择：
--   * context_json / report_json 用 VARCHAR 而不是 TEXT：TEXT 在 H2 里映射成 CLOB，
--     与 Hibernate 对 String 的默认映射不一致，ddl-auto=validate 会失败。
--     VARCHAR + 显式长度在 Postgres 与 H2 上都是同一个类型。
--   * 长度给得宽松（上下文 20000、报告 40000），报告正文不会撑爆。
--   * status 用 VARCHAR(16) 存枚举名（@Enumerated(STRING)），不存序号：
--     加枚举值或调整顺序时不会把历史数据读错。
CREATE TABLE research_task (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    title VARCHAR(200) NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    market VARCHAR(20),
    symbol VARCHAR(32),
    question VARCHAR(2000) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    context_json VARCHAR(20000),
    report_json VARCHAR(40000),
    error_message VARCHAR(1000),
    model VARCHAR(64),
    prompt_tokens INTEGER,
    completion_tokens INTEGER,
    created_at TIMESTAMP NOT NULL,
    started_at TIMESTAMP,
    finished_at TIMESTAMP,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_research_task_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_research_task_user_created ON research_task(user_id, created_at);
CREATE INDEX idx_research_task_status ON research_task(status);