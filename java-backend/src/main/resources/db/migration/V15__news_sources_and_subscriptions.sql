CREATE TABLE news_source (
    id BIGSERIAL PRIMARY KEY,
    source_key VARCHAR(80) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    url VARCHAR(500) NOT NULL UNIQUE,
    category VARCHAR(40) NOT NULL DEFAULT 'general',
    credibility INTEGER NOT NULL DEFAULT 50,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT ck_news_source_credibility CHECK (credibility BETWEEN 0 AND 100)
);

CREATE TABLE news_subscription (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    source_key VARCHAR(80) NOT NULL DEFAULT '',
    topic VARCHAR(80) NOT NULL DEFAULT '',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_news_subscription UNIQUE (user_id, source_key, topic),
    CONSTRAINT fk_news_subscription_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT ck_news_subscription_target CHECK (source_key <> '' OR topic <> '')
);

CREATE INDEX idx_news_subscription_user ON news_subscription(user_id);
CREATE INDEX idx_news_subscription_source ON news_subscription(source_key);

-- 预置 10 个来源；管理员可在后台停用、修改或继续添加来源。
INSERT INTO news_source
    (source_key, name, url, category, credibility, enabled, created_at, updated_at)
VALUES
    ('yahoo_finance', 'Yahoo Finance', 'https://finance.yahoo.com/news/rssindex', 'global', 78, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('cnbc_finance', 'CNBC Finance', 'https://search.cnbc.com/rs/search/combinedcms/view.xml?partnerId=wrss01&id=100003114', 'global', 82, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('marketwatch_top', 'MarketWatch', 'https://feeds.content.dowjones.io/public/rss/mw_topstories', 'markets', 76, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('investing_cn', '英为财情', 'https://cn.investing.com/rss/news.rss', 'markets', 70, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('wsj_markets', 'WSJ Markets', 'https://feeds.a.dj.com/rss/RSSMarketsMain.xml', 'markets', 84, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('cointelegraph', 'Cointelegraph', 'https://cointelegraph.com/rss', 'crypto', 68, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('coindesk', 'CoinDesk', 'https://www.coindesk.com/arc/outboundfeeds/rss/', 'crypto', 72, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('ft_markets', 'Financial Times Markets', 'https://www.ft.com/markets?format=rss', 'global', 88, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('marketwatch_topstories', 'MarketWatch Top Stories', 'https://www.marketwatch.com/rss/topstories', 'markets', 76, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('nasdaq_market', 'Nasdaq Market News', 'https://www.nasdaq.com/feed/rssoutbound?category=Markets', 'markets', 84, TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
