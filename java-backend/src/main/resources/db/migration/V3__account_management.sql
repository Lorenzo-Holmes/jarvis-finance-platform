ALTER TABLE users ADD COLUMN role VARCHAR(24) NOT NULL DEFAULT 'USER';

CREATE TABLE ai_quota (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    daily_request_limit INTEGER NOT NULL DEFAULT 100,
    daily_request_used INTEGER NOT NULL DEFAULT 0,
    monthly_token_limit BIGINT NOT NULL DEFAULT 0,
    monthly_token_used BIGINT NOT NULL DEFAULT 0,
    reset_date DATE NOT NULL,
    period_month VARCHAR(7) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_ai_quota_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE TABLE user_feature_permission (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    feature_key VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_feature_permission UNIQUE (user_id, feature_key),
    CONSTRAINT fk_user_feature_permission_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_feature_permission_user ON user_feature_permission(user_id);

CREATE TABLE oauth_account (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    provider VARCHAR(32) NOT NULL,
    provider_user_id VARCHAR(128) NOT NULL,
    provider_login VARCHAR(120),
    email VARCHAR(120),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_oauth_provider_subject UNIQUE (provider, provider_user_id),
    CONSTRAINT fk_oauth_account_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_oauth_account_user ON oauth_account(user_id);

CREATE TABLE email_verification_code (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(120) NOT NULL,
    purpose VARCHAR(24) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    verified_at TIMESTAMP,
    used_at TIMESTAMP,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_email_verification_lookup
    ON email_verification_code(email, purpose, created_at);
