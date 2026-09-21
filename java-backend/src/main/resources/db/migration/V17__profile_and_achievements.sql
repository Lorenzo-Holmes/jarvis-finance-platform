ALTER TABLE users ADD COLUMN avatar_url VARCHAR(500);
ALTER TABLE users ADD COLUMN signature VARCHAR(160);
ALTER TABLE users ADD COLUMN contact_info VARCHAR(200);
ALTER TABLE users ADD COLUMN profile_public BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE users ADD COLUMN contact_public BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN activity_public BOOLEAN NOT NULL DEFAULT TRUE;

CREATE TABLE user_achievement (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    achievement_key VARCHAR(48) NOT NULL,
    unlocked_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_achievement UNIQUE (user_id, achievement_key),
    CONSTRAINT fk_user_achievement_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_achievement_user_unlocked ON user_achievement(user_id, unlocked_at DESC);
