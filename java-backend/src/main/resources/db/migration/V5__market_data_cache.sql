CREATE TABLE market_data_cache (
    id BIGSERIAL PRIMARY KEY,
    cache_key VARCHAR(300) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    market VARCHAR(32) NOT NULL,
    symbol VARCHAR(64) NOT NULL,
    bar_interval VARCHAR(8),
    payload TEXT NOT NULL,
    source VARCHAR(80),
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_market_data_cache_key UNIQUE (cache_key)
);

CREATE INDEX idx_market_data_cache_lookup
    ON market_data_cache (market, symbol, kind, bar_interval);
