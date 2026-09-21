# JARVIS 社交与社区 Build Spec V1.0

## 1. 数据模型

新增迁移 `V16__social_community.sql`：

- `community_group`
  - `owner_user_id`
  - `name`
  - `description`
  - `visibility` (`OPEN` / `CLOSED`)
  - `created_at / updated_at`
- `community_group_member`
  - `group_id / user_id`
  - `role` (`OWNER` / `MEMBER`)
  - 唯一约束 `(group_id,user_id)`
- `community_post`
  - `author_user_id`
  - 可空 `group_id`
  - `content`
  - 可空 `reference_type / reference_id`
  - `created_at`
- `direct_message`
  - `sender_user_id / recipient_user_id`
  - `content`
  - `created_at / read_at`
- `user_activity`
  - `user_id`
  - `activity_type`
  - `summary`
  - `reference_type / reference_id`
  - `created_at`

管理员 `user_group` 表保持不变。

## 2. Java 模块

新增 `com.jarvis.research.social`：

- JPA Entities / Repositories
- `SocialDtos`
- `SocialService`
- `AchievementService`（社交行为触发勋章刷新）

新增 `SocialController`，统一挂载 `/api/social/**`。

## 3. API

### 用户

- `GET /api/social/users?query=`
- `GET /api/social/users/{userId}`
- `GET /api/social/users/{userId}/activity`

### 小组

- `GET /api/social/groups`
- `POST /api/social/groups`
- `GET /api/social/groups/{groupId}`
- `POST /api/social/groups/{groupId}/join`
- `DELETE /api/social/groups/{groupId}/leave`
- `POST /api/social/groups/{groupId}/members/{userId}`
- `GET /api/social/groups/{groupId}/posts`
- `POST /api/social/groups/{groupId}/posts`

### 动态

- `GET /api/social/feed`
- `POST /api/social/posts`

### 私信

- `GET /api/social/messages/conversations`
- `GET /api/social/messages/{userId}`
- `POST /api/social/messages/{userId}`

## 4. Feed 访问规则

公共 Feed SQL/服务层过滤：

- `group_id IS NULL`；或
- 所属小组 `visibility=OPEN`。

关闭小组详情读取时，必须先验证成员关系。

## 5. 会话实现

消息仓储只提供：

- 当前用户参与的最近消息；
- 两用户之间的线程；
- 将 `sender=other AND recipient=current` 的消息批量标记已读。

会话列表在服务层按对方 userId 去重，取最新消息作为摘要。

## 6. 前端

新增 `CommunityPage.vue`，采用四个标签：

- 社区动态
- 研究小组
- 用户发现
- 私信

新增分享工具 `utils/socialShare.js`：

- `shareToWeibo`
- `shareToXiaohongshu`
- `copyShareText`

对微博写操作只打开分享页。小红书使用 Web Share / clipboard 回退。

## 7. 测试

- Java service/controller：组权限、CLOSED 访问、私信归属、Feed 过滤。
- Frontend source contract：模块入口、API client、分享回退、私信 UI。
- Flyway + PostgreSQL CI。
