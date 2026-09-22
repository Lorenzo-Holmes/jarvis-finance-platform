-- 每次 Agent 运行都持久绑定研究对象，保证重放、审计和模型工具使用同一标的。
ALTER TABLE agent_run ADD COLUMN market VARCHAR(32) NOT NULL DEFAULT 'gold_etf';
ALTER TABLE agent_run ADD COLUMN symbol VARCHAR(64) NOT NULL DEFAULT 'sh518850';
ALTER TABLE agent_run ADD COLUMN instrument_name VARCHAR(120) NOT NULL DEFAULT '黄金ETF华夏';
