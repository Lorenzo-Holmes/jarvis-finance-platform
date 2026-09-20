CREATE TABLE user_group (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(80) NOT NULL UNIQUE,
    description VARCHAR(500),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE user_group_member (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_group_member UNIQUE (group_id, user_id),
    CONSTRAINT fk_user_group_member_group FOREIGN KEY (group_id) REFERENCES user_group(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_group_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_group_member_group ON user_group_member(group_id);

CREATE TABLE group_ai_quota (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL UNIQUE,
    daily_request_limit INTEGER NOT NULL DEFAULT 100,
    daily_request_used INTEGER NOT NULL DEFAULT 0,
    monthly_token_limit BIGINT NOT NULL DEFAULT 0,
    monthly_token_used BIGINT NOT NULL DEFAULT 0,
    reset_date DATE NOT NULL,
    period_month VARCHAR(7) NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_group_ai_quota_group FOREIGN KEY (group_id) REFERENCES user_group(id) ON DELETE CASCADE
);

CREATE TABLE group_feature_permission (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    feature_key VARCHAR(80) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_group_feature_permission UNIQUE (group_id, feature_key),
    CONSTRAINT fk_group_feature_permission_group FOREIGN KEY (group_id) REFERENCES user_group(id) ON DELETE CASCADE
);

CREATE INDEX idx_group_feature_permission_group ON group_feature_permission(group_id);
