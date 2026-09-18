package com.jarvis.research.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 站内通知的读写。
 *
 * <p>写入侧只有一个入口 {@link #raise}，把"要不要通知、什么时候不该通知"的判断
 * 全部留给调用方（当前是 {@code ScheduledTaskNotificationListener}），
 * 本类只负责三件事：<strong>合并窗口内的重复事件、裁剪超量历史、保证越权取不到</strong>。</p>
 *
 * <p>几条刻意的取舍：</p>
 * <ol>
 *   <li><b>写入失败不该影响业务</b>：调用方（任务执行链路）必须自行吞掉异常。
 *       本类不吞 —— 否则连"通知写不进去"这件事都会被静默掉。</li>
 *   <li><b>裁剪只做一批</b>：单次写入最多删 {@value #PRUNE_BATCH} 条。
 *       为了把超量清干净而做无界删除，会把一次突发写入变成一次大事务。</li>
 *   <li><b>删除用物理删</b>：通知不是审计记录（审计在 {@code audit_events}），
 *       用户主动删掉的东西没有保留义务；软删只会让"每人 200 条"的裁剪逻辑复杂化。</li>
 * </ol>
 */
@Service
@Slf4j
public class NotificationService {

    /** 与 {@code user_notification.title} 列宽一致。 */
    static final int MAX_TITLE = 120;
    /** 与 {@code user_notification.body} 列宽一致。 */
    static final int MAX_BODY = 1000;
    /** 与 {@code user_notification.dedup_key} 列宽一致。 */
    static final int MAX_DEDUP_KEY = 128;
    /** 与 {@code user_notification.link_kind} 列宽一致。 */
    static final int MAX_LINK_KIND = 24;

    static final int MAX_PAGE_SIZE = 50;

    /** 每次写入最多裁掉多少条（见类注释第 2 条）。 */
    static final int PRUNE_BATCH = 50;

    private final UserNotificationRepository repository;
    private final int keepPerUser;
    private final long dedupWindowMinutes;

    public NotificationService(UserNotificationRepository repository,
                               @Value("${jarvis.notification.keep-per-user:200}") int keepPerUser,
                               @Value("${jarvis.notification.dedup-window-minutes:10}") long dedupWindowMinutes) {
        this.repository = repository;
        this.keepPerUser = Math.max(10, keepPerUser);
        this.dedupWindowMinutes = Math.max(0, dedupWindowMinutes);
    }

    // ------------------------------------------------------------------ 写

    /**
     * 产生一条通知；窗口内已有同类事件时改为合并。
     *
     * @param linkKind 引用类型，可为 null
     * @param linkRef  引用 id，可为 null（仅作去重键与跳转用）
     * @return 新建或合并后的那条通知
     */
    @Transactional
    public UserNotification raise(Long userId, NotificationType type, NotificationLevel level,
                                  String title, String body, String linkKind, Long linkRef) {
        if (userId == null || type == null || level == null) {
            throw new IllegalArgumentException("通知的 userId / type / level 不能为空");
        }
        String safeTitle = truncate(title, MAX_TITLE);
        if (safeTitle == null || safeTitle.isBlank()) {
            // 没有标题的通知在列表里就是一行空白，等于没通知 —— 这种几乎必然是调用方的 bug。
            throw new IllegalArgumentException("通知标题不能为空");
        }

        String dedupKey = buildDedupKey(userId, type, linkRef);
        LocalDateTime now = LocalDateTime.now();

        Optional<UserNotification> existing = repository
                .findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                        userId, dedupKey, now.minusMinutes(dedupWindowMinutes));

        if (existing.isPresent()) {
            UserNotification merged = existing.get();
            merged.setTitle(safeTitle);
            merged.setBody(truncate(body, MAX_BODY));
            // 等级可能变化（例如同一任务的失败升级为风险），以最新一次为准。
            merged.setLevel(level);
            merged.setRepeatCount(nz(merged.getRepeatCount()) + 1);
            merged.setLastSeenAt(now);
            // 它又发生了一次 → 重新变未读，否则用户看过的旧记录会把新故障悄悄吃掉。
            merged.setReadAt(null);
            return repository.save(merged);
        }

        UserNotification created = repository.save(UserNotification.builder()
                .userId(userId)
                .type(type)
                .level(level)
                .title(safeTitle)
                .body(truncate(body, MAX_BODY))
                .linkKind(truncate(linkKind, MAX_LINK_KIND))
                .linkRef(linkRef)
                .dedupKey(dedupKey)
                .repeatCount(1)
                .createdAt(now)
                .lastSeenAt(now)
                .build());

        int pruned = pruneOlderThanKeep(userId);
        if (pruned > 0) {
            log.debug("通知超出保留条数，已裁剪 {} 条。userId={} keep={}", pruned, userId, keepPerUser);
        }
        return created;
    }

    // ------------------------------------------------------------------ 读

    public Page<UserNotification> list(Long userId, boolean unreadOnly, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return repository.findForUser(userId, unreadOnly, PageRequest.of(safePage, safeSize));
    }

    /** 未读数，供前端角标。 */
    public long unreadCount(Long userId) {
        return repository.countByUserIdAndReadAtIsNull(userId);
    }

    /** 标记已读；不存在或不属于该用户都返回 404（不区分，避免泄漏他人通知是否存在）。 */
    @Transactional
    public UserNotification markRead(Long userId, Long notificationId) {
        UserNotification notification = require(userId, notificationId);
        if (notification.isUnread()) {
            notification.setReadAt(LocalDateTime.now());
            return repository.save(notification);
        }
        return notification;
    }

    /** 全部已读，返回本次标记的条数。 */
    @Transactional
    public int markAllRead(Long userId) {
        return repository.markAllRead(userId, LocalDateTime.now());
    }

    @Transactional
    public void delete(Long userId, Long notificationId) {
        repository.delete(require(userId, notificationId));
    }

    // ------------------------------------------------------------------ 内部

    private UserNotification require(Long userId, Long notificationId) {
        return repository.findByIdAndUserId(notificationId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "通知不存在"));
    }

    /**
     * 裁掉超出保留条数的部分，最多 {@value #PRUNE_BATCH} 条。
     *
     * <p>按 {@code lastSeenAt} 倒序保留"最近发生的"：正在持续发生的故障必须留下，
     * 被裁掉的应当是最久没再出现的那批。</p>
     */
    private int pruneOlderThanKeep(Long userId) {
        List<Long> ids = repository.findIdsByUserIdOrderByLastSeenAtDesc(
                userId, PageRequest.of(0, keepPerUser + PRUNE_BATCH));
        if (ids.size() <= keepPerUser) {
            return 0;
        }
        List<Long> overflow = List.copyOf(ids.subList(keepPerUser, ids.size()));
        repository.deleteAllByIdInBatch(overflow);
        return overflow.size();
    }

    /** 去重键：{@code <userId>:<type>:<refId>}；没有引用时用 {@code -} 占位，保持键形状稳定。 */
    static String buildDedupKey(Long userId, NotificationType type, Long linkRef) {
        String key = userId + ":" + type.name() + ":" + (linkRef == null ? "-" : linkRef);
        return truncate(key, MAX_DEDUP_KEY);
    }

    /** 截断到列宽以内：通知是"提醒"，宁可丢尾巴也不能因为超长让整条写不进去。 */
    static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static int nz(Integer value) {
        return value == null ? 0 : value;
    }
}
