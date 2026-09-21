# JARVIS 用户个性化与成就体系 Build Spec V1.0

## 1. 数据模型

新增迁移 `V17__profile_and_achievements.sql`。

### users 增量列

- `avatar_url VARCHAR(500)`
- `signature VARCHAR(160)`
- `contact_info VARCHAR(200)`
- `profile_public BOOLEAN DEFAULT TRUE`
- `contact_public BOOLEAN DEFAULT FALSE`
- `activity_public BOOLEAN DEFAULT TRUE`

### user_achievement

- `user_id`
- `achievement_key`
- `unlocked_at`
- 唯一约束 `(user_id,achievement_key)`

## 2. Java

扩展 `User` 与 `AuthDtos.UserInfo`，但认证响应不会因此泄露其他用户信息；其他用户资料由 SocialService 单独裁剪。

新增 `AchievementService`：

- 查询 `AuditEventRepository` 计算登录 streak；
- 查询 `ResearchTaskRepository` 统计成功任务；
- 查询 `ScheduledTaskRepository` 统计有效任务；
- 查询 `ScheduledTaskRunRepository` 统计成功执行次数，用于任务达成勋章；
- 查询社交仓储统计动态/小组/私信；
- 幂等写入 `user_achievement`；
- 解锁时写入 `user_activity`。

新增 API：

- `GET /api/social/profile`
- `PATCH /api/social/profile`
- `GET /api/social/achievements`

## 3. 前端

新增一级工作区 `个人中心`：

- Profile hero
- 编辑资料
- 隐私设置
- 连续登录与使用统计
- 成就网格
- 最近动态

头像仅接受 HTTPS URL；浏览器端做基础格式校验，服务端再次校验。

## 4. 隐私输出裁剪

对其他用户：

- `profilePublic=false`：只返回 `id/displayName/profilePublic`；
- `profilePublic=true`：增加 avatar/signature；
- `contactPublic=true`：增加 contactInfo；
- `activityPublic=true`：允许读取活动和成就。

## 5. 成就评估时机

- `GET /achievements`：全量补评估；
- 资料更新：立即评估 PROFILE_COMPLETE；
- 发帖/入组/私信：立即评估社交类成就。

研究类/自动化类不向已有业务服务引入反向依赖，采用成就页读取时补评估，降低耦合。

## 6. 测试

- Profile validation / privacy output；
- Achievement idempotency；
- login streak；
- research/scheduled/social achievement evaluation；
- 前端个人中心/模块/API contract。
