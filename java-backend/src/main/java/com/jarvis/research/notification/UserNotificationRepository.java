package com.jarvis.research.notification;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 通知仓储。
 *
 * <p>与 {@code ScheduledTaskRepository} 同一约定：查询一律**带 userId**，
 * 越权在数据访问这一层就做不到，而不是指望每个调用点都记得校验。</p>
 */
public interface UserNotificationRepository extends JpaRepository<UserNotification, Long> {

    /**
     * 列表：按等级权重（RISK → WARN → INFO）再按最近一次发生时间倒序。
     *
     * <p>⚠️ 排序不能写成 {@code order by n.level}：数据库里存的是枚举名，
     * 字母序是 {@code INFO < RISK < WARN}，会把最紧急的 RISK 排到中间。
     * 这里的 CASE 与 {@link NotificationLevel#rank()} 一一对应，
     * 由仓储测试钉住"RISK 确实排在最前"。</p>
     *
     * <p>显式给出 {@code countQuery}：带 {@code order by} 的查询让 Spring Data
     * 自动派生 count 语句会带着排序一起拼，既冗余又容易在 H2 上出错。</p>
     */
    @Query(value = "select n from UserNotification n "
            + "where n.userId = :userId and (:unreadOnly = false or n.readAt is null) "
            + "order by case n.level "
            + "  when com.jarvis.research.notification.NotificationLevel.RISK then 0 "
            + "  when com.jarvis.research.notification.NotificationLevel.WARN then 1 "
            + "  else 2 end asc, n.lastSeenAt desc",
            countQuery = "select count(n) from UserNotification n "
                    + "where n.userId = :userId and (:unreadOnly = false or n.readAt is null)")
    Page<UserNotification> findForUser(@Param("userId") Long userId,
                                       @Param("unreadOnly") boolean unreadOnly,
                                       Pageable pageable);

    long countByUserIdAndReadAtIsNull(Long userId);

    /** 取单条时必须同时给用户，避免越权读/改别人的通知。 */
    Optional<UserNotification> findByIdAndUserId(Long id, Long userId);

    /**
     * 去重窗口内的那条通知。
     *
     * <p>按 {@code lastSeenAt} 而不是 {@code createdAt} 判窗口：合并过的记录
     * 时间基准已经推到最近一次，用首次时间会让"持续发生的故障"在窗口失效后
     * 又新开一条。</p>
     */
    Optional<UserNotification> findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
            Long userId, String dedupKey, LocalDateTime windowStart);

    /** 保留策略用：按最近一次发生时间倒序取 id，超出保留条数的裁剪掉。 */
    @Query("select n.id from UserNotification n where n.userId = :userId order by n.lastSeenAt desc")
    List<Long> findIdsByUserIdOrderByLastSeenAtDesc(@Param("userId") Long userId, Pageable pageable);

    /**
     * 全部标记已读。
     *
     * <p>{@code clearAutomatically} 是为了让同事务内的后续读拿到新状态 ——
     * 批量更新绕过了一级缓存，不清会让紧随其后的未读数查询返回旧值。</p>
     */
    @Modifying(clearAutomatically = true)
    @Query("update UserNotification n set n.readAt = :readAt "
            + "where n.userId = :userId and n.readAt is null")
    int markAllRead(@Param("userId") Long userId, @Param("readAt") LocalDateTime readAt);
}
