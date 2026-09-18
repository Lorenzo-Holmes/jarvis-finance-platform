package com.jarvis.research.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 通知仓储的真实 SQL 行为。
 *
 * <p>为什么必须用真库测而不是 mock：本仓储里有两条**只有真跑 SQL 才知道对错**的逻辑 ——</p>
 * <ol>
 *   <li><b>等级排序的 CASE 表达式</b>：等级以枚举名入库，字母序是
 *       {@code INFO &lt; RISK &lt; WARN}，直接 {@code order by level} 会把最紧急的 RISK 排到中间。
 *       排序是 PRD「高风险事件优先提醒」的唯一实现，所以这里造一条"RISK 但时间最早"的数据，
 *       断言它仍排第一 —— 若 CASE 写错或将来被谁改回 {@code order by level}，这条立刻红。</li>
 *   <li><b>{@code dedup_key} 没有唯一约束</b>：去重是"窗口内合并"，窗口外必须能再写一条。
 *       这里显式插入两条同键记录并断言都能落库 —— 若谁给它加了唯一约束，这条会失败。</li>
 * </ol>
 *
 * <p>表由 Flyway 建（{@code ddl-auto=validate}），与另两张定时任务表的做法一致：
 * 要验的是"迁移脚本产出的表"，不是"Hibernate 按实体猜出来的表"。</p>
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:notification-repo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.flyway.enabled=true"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserNotificationRepositoryTest {

    /**
     * ⚠️ 必须是实例字段、且从库里查回来，不能写死 1 / 2：
     * 每个测试各跑一次 {@code @BeforeEach}，而事务回滚**不会**回退 identity 序列 ——
     * 第二个测试插入的 users 拿到的是 3、4，写死 1 会直接撞外键。
     */
    private long user;
    private long otherUser;

    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private UserNotificationRepository repository;

    @BeforeEach
    void createUsers() {
        jdbcTemplate.update("INSERT INTO users (email, password_hash) VALUES (?, ?)",
                "notify-a@example.com", "not-a-real-hash");
        jdbcTemplate.update("INSERT INTO users (email, password_hash) VALUES (?, ?)",
                "notify-b@example.com", "not-a-real-hash");
        user = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "notify-a@example.com");
        otherUser = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, "notify-b@example.com");
    }

    @Test
    void theRiskLevelSortsFirstEvenThoughItIsTheOldest() {
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        repository.save(notification(user, NotificationLevel.INFO, "信息类", base));
        repository.save(notification(user, NotificationLevel.WARN, "任务失败", base.plusHours(1)));
        repository.save(notification(user, NotificationLevel.RISK, "风险命中", base.plusHours(2)));

        Page<UserNotification> page = repository.findForUser(user, false, PageRequest.of(0, 10));

        assertEquals(List.of("RISK", "WARN", "INFO"),
                page.getContent().stream().map(n -> n.getLevel().name()).toList(),
                "高风险必须排最前 —— 按枚举名字母序排会得到 INFO < RISK < WARN，那是错的");
    }

    @Test
    void withinTheSameLevelTheMostRecentComesFirst() {
        LocalDateTime base = LocalDateTime.now().minusHours(3);
        repository.save(notification(user, NotificationLevel.WARN, "先发生", base));
        repository.save(notification(user, NotificationLevel.WARN, "后发生", base.plusHours(1)));

        Page<UserNotification> page = repository.findForUser(user, false, PageRequest.of(0, 10));

        assertEquals("后发生", page.getContent().get(0).getTitle());
    }

    @Test
    void unreadOnlyFiltersOutWhatHasBeenRead() {
        UserNotification unread = repository.save(notification(user, NotificationLevel.WARN, "未读", LocalDateTime.now()));
        UserNotification read = notification(user, NotificationLevel.WARN, "已读", LocalDateTime.now());
        read.setReadAt(LocalDateTime.now());
        repository.save(read);

        Page<UserNotification> page = repository.findForUser(user, true, PageRequest.of(0, 10));

        assertEquals(1, page.getTotalElements());
        assertEquals(unread.getId(), page.getContent().get(0).getId());
        assertEquals(2, repository.findForUser(user, false, PageRequest.of(0, 10)).getTotalElements());
    }

    @Test
    void unreadCountIsPerUser() {
        repository.save(notification(user, NotificationLevel.WARN, "A 的未读", LocalDateTime.now()));
        repository.save(notification(otherUser, NotificationLevel.WARN, "B 的未读", LocalDateTime.now()));

        assertEquals(1, repository.countByUserIdAndReadAtIsNull(user));
        assertEquals(1, repository.countByUserIdAndReadAtIsNull(otherUser));
    }

    @Test
    void notificationsAreInvisibleAcrossUsers() {
        UserNotification mine = repository.save(notification(user, NotificationLevel.RISK, "我的", LocalDateTime.now()));

        assertTrue(repository.findByIdAndUserId(mine.getId(), user).isPresent());
        assertFalse(repository.findByIdAndUserId(mine.getId(), otherUser).isPresent(),
                "别人的通知必须在数据访问层就取不到，而不是靠每个调用点记得校验");
    }

    @Test
    void theDedupKeyIsDeliberatelyNotUnique() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(notificationWithKey(user, "1:TASK_FAILED:9", now.minusHours(5)));
        repository.save(notificationWithKey(user, "1:TASK_FAILED:9", now));

        assertEquals(2, repository.count(),
                "窗口外的同类事件必须能再写一条：把 dedup_key 变成唯一约束会抹掉"
                        + "「上周坏过、这周又坏」这个信息");
    }

    @Test
    void theDedupLookupHonoursTheWindow() {
        LocalDateTime now = LocalDateTime.now();
        repository.save(notificationWithKey(user, "1:TASK_FAILED:9", now.minusMinutes(30)));

        // 查询语义是 lastSeenAt > windowStart：窗口起点越早，越容易命中。
        Optional<UserNotification> insideWindow = repository
                .findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                        user, "1:TASK_FAILED:9", now.minusMinutes(60));
        Optional<UserNotification> outsideWindow = repository
                .findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                        user, "1:TASK_FAILED:9", now.minusMinutes(10));

        assertTrue(insideWindow.isPresent(), "60 分钟前发生的记录应当落在 60 分钟窗口内");
        assertFalse(outsideWindow.isPresent(), "30 分钟前的记录不该落在 10 分钟窗口内");
    }

    @Test
    void markAllReadOnlyTouchesThatUsersUnreadRows() {
        repository.save(notification(user, NotificationLevel.WARN, "A1", LocalDateTime.now()));
        repository.save(notification(user, NotificationLevel.WARN, "A2", LocalDateTime.now()));
        repository.save(notification(otherUser, NotificationLevel.WARN, "B1", LocalDateTime.now()));

        int updated = repository.markAllRead(user, LocalDateTime.now());

        assertEquals(2, updated);
        assertEquals(0, repository.countByUserIdAndReadAtIsNull(user));
        assertEquals(1, repository.countByUserIdAndReadAtIsNull(otherUser), "别人的未读不该被清");
    }

    @Test
    void pruningReadsIdsInMostRecentFirstOrder() {
        LocalDateTime base = LocalDateTime.now().minusHours(5);
        repository.save(notification(user, NotificationLevel.WARN, "最久", base));
        repository.save(notification(user, NotificationLevel.WARN, "中间", base.plusHours(1)));
        repository.save(notification(user, NotificationLevel.WARN, "最近", base.plusHours(2)));

        List<Long> ids = repository.findIdsByUserIdOrderByLastSeenAtDesc(user, Pageable.ofSize(10));

        assertEquals(3, ids.size());
        assertEquals("最近", repository.findById(ids.get(0)).orElseThrow().getTitle());
        assertEquals("最久", repository.findById(ids.get(2)).orElseThrow().getTitle());
    }

    private static UserNotification notification(Long userId, NotificationLevel level,
                                                 String title, LocalDateTime lastSeenAt) {
        UserNotification notification = notificationWithKey(userId, userId + ":" + level.name() + "-" + title, lastSeenAt);
        notification.setLevel(level);
        notification.setTitle(title);
        return notification;
    }

    private static UserNotification notificationWithKey(Long userId, String dedupKey, LocalDateTime lastSeenAt) {
        return UserNotification.builder()
                .userId(userId)
                .type(NotificationType.TASK_FAILED)
                .level(NotificationLevel.WARN)
                .title("标题")
                .dedupKey(dedupKey)
                .repeatCount(1)
                .createdAt(lastSeenAt)
                .lastSeenAt(lastSeenAt)
                .build();
    }
}
