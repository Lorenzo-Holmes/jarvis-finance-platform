CREATE TABLE community_group (
    id BIGSERIAL PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    name VARCHAR(80) NOT NULL,
    description VARCHAR(500),
    visibility VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_community_group_owner FOREIGN KEY (owner_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_community_group_visibility_updated ON community_group(visibility, updated_at DESC);
CREATE INDEX idx_community_group_owner ON community_group(owner_user_id);

CREATE TABLE community_group_member (
    id BIGSERIAL PRIMARY KEY,
    group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_community_group_member UNIQUE (group_id, user_id),
    CONSTRAINT fk_community_group_member_group FOREIGN KEY (group_id) REFERENCES community_group(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_group_member_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_community_group_member_user ON community_group_member(user_id);
CREATE INDEX idx_community_group_member_group ON community_group_member(group_id);

CREATE TABLE community_post (
    id BIGSERIAL PRIMARY KEY,
    author_user_id BIGINT NOT NULL,
    group_id BIGINT,
    content VARCHAR(4000) NOT NULL,
    reference_type VARCHAR(32),
    reference_id VARCHAR(120),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_community_post_author FOREIGN KEY (author_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_community_post_group FOREIGN KEY (group_id) REFERENCES community_group(id) ON DELETE CASCADE
);

CREATE INDEX idx_community_post_created ON community_post(created_at DESC);
CREATE INDEX idx_community_post_group_created ON community_post(group_id, created_at DESC);
CREATE INDEX idx_community_post_author ON community_post(author_user_id, created_at DESC);

CREATE TABLE direct_message (
    id BIGSERIAL PRIMARY KEY,
    sender_user_id BIGINT NOT NULL,
    recipient_user_id BIGINT NOT NULL,
    content VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    read_at TIMESTAMP,
    CONSTRAINT fk_direct_message_sender FOREIGN KEY (sender_user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_direct_message_recipient FOREIGN KEY (recipient_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_direct_message_sender_created ON direct_message(sender_user_id, created_at DESC);
CREATE INDEX idx_direct_message_recipient_created ON direct_message(recipient_user_id, created_at DESC);

CREATE TABLE user_activity (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    activity_type VARCHAR(32) NOT NULL,
    summary VARCHAR(500) NOT NULL,
    reference_type VARCHAR(32),
    reference_id VARCHAR(120),
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_user_activity_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_activity_user_created ON user_activity(user_id, created_at DESC);
