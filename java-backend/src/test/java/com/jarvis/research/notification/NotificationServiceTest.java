package com.jarvis.research.notification;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 通知服务的行为断言。
 *
 * <p>重点在三处容易被"能跑"掩盖的地方：</p>
 * <ul>
 *   <li><b>窗口内合并</b>：合并必须让记录**重新变未读** —— 否则用户看过一次之后，
 *       同一个故障继续发生就再也不会出现在未读里，这恰恰是最需要被提醒的情况。</li>
 *   <li><b>裁剪只裁一批</b>：`keepPerUser` 是保留上限，但单次写入最多删 50 条，
 *       避免一次突发写入变成一次无界删除。</li>
 *   <li><b>越权一律 404</b>：不存在与不属于该用户返回同样的错误，不泄漏他人通知是否存在。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServiceTest {

    private static final long USER = 7L;
    private static final long NOTIFICATION_ID = 100L;

    /** 与服务里的下限一致（keepPerUser 会被夹到 ≥10）。 */
    private static final int KEEP = 10;

    @Mock
    private UserNotificationRepository repository;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(repository, KEEP, 10);
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------ 写入

    @Test
    void raiseWritesTheFirstOccurrence() {
        UserNotification created = service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN,
                "定时任务执行失败：每日行情扫描", "上游行情源超时", "SCHEDULED_TASK", 9L);

        assertEquals(1, created.getRepeatCount());
        assertEquals(USER, created.getUserId());
        assertNull(created.getReadAt(), "新通知必须是未读");
        assertNotNull(created.getCreatedAt());
        assertEquals(created.getCreatedAt(), created.getLastSeenAt(), "首次写入两个时间戳相同");
        assertEquals("7:TASK_FAILED:9", created.getDedupKey());
    }

    @Test
    void raiseMergesIntoTheExistingNotificationInsideTheWindow() {
        UserNotification existing = existingNotification();
        existing.setReadAt(LocalDateTime.now().minusMinutes(1));
        when(repository.findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                eq(USER), eq("7:TASK_FAILED:9"), any())).thenReturn(Optional.of(existing));

        UserNotification merged = service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN,
                "定时任务执行失败：每日行情扫描", "上游行情源超时（第 2 次）", "SCHEDULED_TASK", 9L);

        assertEquals(2, merged.getRepeatCount(), "窗口内重复发生应当累加次数而不是新写一条");
        assertNull(merged.getReadAt(), "又发生了一次就必须重新变未读，否则新故障会被已读记录吃掉");
        assertTrue(merged.getLastSeenAt().isAfter(existing.getCreatedAt()), "最近一次发生时间要往前推");
        assertTrue(merged.getBody().contains("第 2 次"), "摘要应当以最新一次为准");
    }

    @Test
    void raiseStartsANewNotificationOutsideTheWindow() {
        when(repository.findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                eq(USER), eq("7:TASK_FAILED:9"), any())).thenReturn(Optional.empty());

        UserNotification created = service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN,
                "定时任务执行失败：每日行情扫描", null, "SCHEDULED_TASK", 9L);

        assertEquals(1, created.getRepeatCount());
    }

    @Test
    void theDedupWindowIsPassedToTheLookup() {
        service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN,
                "标题", null, "SCHEDULED_TASK", 9L);

        ArgumentCaptor<LocalDateTime> windowStart = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                eq(USER), eq("7:TASK_FAILED:9"), windowStart.capture());
        // 10 分钟窗口：起点应当落在 [now-11min, now-9min] 之间（避免断言精确到毫秒而抖动）。
        LocalDateTime expected = LocalDateTime.now().minusMinutes(10);
        assertTrue(Math.abs(java.time.Duration.between(expected, windowStart.getValue()).toSeconds()) < 60,
                "窗口起点应当约等于 now-10min，实际：" + windowStart.getValue());
    }

    @Test
    void raiseRejectsABlankTitle() {
        // 没有标题的通知在列表里就是一行空白，等于没通知 —— 这种几乎必然是调用方 bug，直接拒绝。
        assertThrows(IllegalArgumentException.class, () -> service.raise(
                USER, NotificationType.TASK_FAILED, NotificationLevel.WARN, "   ", null, null, null));
    }

    @Test
    void raiseRejectsMissingIdentityFields() {
        assertThrows(IllegalArgumentException.class, () -> service.raise(
                null, NotificationType.TASK_FAILED, NotificationLevel.WARN, "标题", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.raise(
                USER, null, NotificationLevel.WARN, "标题", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> service.raise(
                USER, NotificationType.TASK_FAILED, null, "标题", null, null, null));
    }

    @Test
    void raiseTruncatesOverlongFieldsInsteadOfFailing() {
        // 提醒类数据不该因为"标题长了一点"让整条写不进去 —— 截断后仍然能送达。
        UserNotification created = service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN,
                "标".repeat(300), "体".repeat(3000), "K".repeat(50), 9L);

        assertEquals(NotificationService.MAX_TITLE, created.getTitle().length());
        assertEquals(NotificationService.MAX_BODY, created.getBody().length());
        assertEquals(NotificationService.MAX_LINK_KIND, created.getLinkKind().length());
    }

    @Test
    void dedupKeyShapeIsStableWithoutAReference() {
        assertEquals("7:TASK_FAILED:-", NotificationService.buildDedupKey(USER, NotificationType.TASK_FAILED, null));
        assertEquals("7:RISK_ALERT:12", NotificationService.buildDedupKey(USER, NotificationType.RISK_ALERT, 12L));
    }

    // ------------------------------------------------------------------ 保留策略

    @Test
    void pruningDeletesOnlyTheOverflowBeyondTheKeepLimit() {
        when(repository.findIdsByUserIdOrderByLastSeenAtDesc(eq(USER), any(Pageable.class)))
                .thenReturn(LongStream.rangeClosed(1, KEEP + 3).boxed().toList());

        service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN, "标题", null, null, 9L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> deleted = ArgumentCaptor.forClass(List.class);
        verify(repository).deleteAllByIdInBatch(deleted.capture());
        assertEquals(3, deleted.getValue().size(), "只该裁掉超出保留条数的部分");
        assertEquals(List.of(11L, 12L, 13L), deleted.getValue(),
                "被裁的应当是列表尾部（最久未发生的那批）");
    }

    @Test
    void pruningReadsAtMostOneBatchMoreThanTheKeepLimit() {
        // "只裁一批"体现在读取阶段：即使库里堆了几千条，也只取 keep+50 个 id，
        // 不把一次突发写入变成一次无界删除。
        when(repository.findIdsByUserIdOrderByLastSeenAtDesc(eq(USER), any(Pageable.class)))
                .thenReturn(LongStream.rangeClosed(1, KEEP).boxed().toList());

        service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN, "标题", null, null, 9L);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findIdsByUserIdOrderByLastSeenAtDesc(eq(USER), pageable.capture());
        assertEquals(KEEP + NotificationService.PRUNE_BATCH, pageable.getValue().getPageSize());
    }

    @Test
    void pruningIsSkippedWhenWithinTheKeepLimit() {
        when(repository.findIdsByUserIdOrderByLastSeenAtDesc(eq(USER), any(Pageable.class)))
                .thenReturn(List.of(1L, 2L, 3L));

        service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN, "标题", null, null, 9L);

        verify(repository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    void mergingDoesNotTriggerPruning() {
        // 合并只更新已有行，表不会变大，没必要再查一次。
        when(repository.findFirstByUserIdAndDedupKeyAndLastSeenAtAfterOrderByLastSeenAtDesc(
                eq(USER), any(), any())).thenReturn(Optional.of(existingNotification()));

        service.raise(USER, NotificationType.TASK_FAILED, NotificationLevel.WARN, "标题", null, null, 9L);

        verify(repository, never()).findIdsByUserIdOrderByLastSeenAtDesc(anyLong(), any(Pageable.class));
    }

    // ------------------------------------------------------------------ 读与状态

    @Test
    void listClampsThePageSize() {
        when(repository.findForUser(eq(USER), eq(false), any(Pageable.class)))
                .thenReturn(new PageImpl<>(new ArrayList<>()));

        service.list(USER, false, -2, 9999);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findForUser(eq(USER), eq(false), pageable.capture());
        assertEquals(0, pageable.getValue().getPageNumber());
        assertEquals(NotificationService.MAX_PAGE_SIZE, pageable.getValue().getPageSize());
    }

    @Test
    void unreadCountDelegatesToTheRepository() {
        when(repository.countByUserIdAndReadAtIsNull(USER)).thenReturn(4L);

        assertEquals(4L, service.unreadCount(USER));
    }

    @Test
    void markReadStampsTheTimeOnlyOnce() {
        UserNotification unread = existingNotification();
        when(repository.findByIdAndUserId(NOTIFICATION_ID, USER)).thenReturn(Optional.of(unread));

        UserNotification result = service.markRead(USER, NOTIFICATION_ID);

        assertNotNull(result.getReadAt());
        assertFalse(result.isUnread());
    }

    @Test
    void markReadIsIdempotent() {
        UserNotification alreadyRead = existingNotification();
        alreadyRead.setReadAt(LocalDateTime.now().minusHours(1));
        when(repository.findByIdAndUserId(NOTIFICATION_ID, USER)).thenReturn(Optional.of(alreadyRead));

        service.markRead(USER, NOTIFICATION_ID);

        // 已是已读就不该再写一次库（否则"什么时候读的"会被刷新成错误的时间）。
        verify(repository, never()).save(any());
    }

    @Test
    void markReadTreatsSomeoneElsesNotificationAsMissing() {
        when(repository.findByIdAndUserId(NOTIFICATION_ID, USER)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.markRead(USER, NOTIFICATION_ID));

        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void markAllReadDelegatesAndReturnsTheCount() {
        when(repository.markAllRead(eq(USER), any(LocalDateTime.class))).thenReturn(5);

        assertEquals(5, service.markAllRead(USER));
    }

    @Test
    void deleteGoesThroughTheOwnershipCheck() {
        when(repository.findByIdAndUserId(NOTIFICATION_ID, USER)).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.delete(USER, NOTIFICATION_ID));
        verify(repository, never()).delete(any());
    }

    @Test
    void deleteRemovesTheOwnedNotification() {
        UserNotification mine = existingNotification();
        when(repository.findByIdAndUserId(NOTIFICATION_ID, USER)).thenReturn(Optional.of(mine));

        service.delete(USER, NOTIFICATION_ID);

        verify(repository).delete(mine);
    }

    private static UserNotification existingNotification() {
        LocalDateTime now = LocalDateTime.now().minusMinutes(2);
        return UserNotification.builder()
                .id(NOTIFICATION_ID)
                .userId(USER)
                .type(NotificationType.TASK_FAILED)
                .level(NotificationLevel.WARN)
                .title("定时任务执行失败：每日行情扫描")
                .dedupKey("7:TASK_FAILED:9")
                .repeatCount(1)
                .createdAt(now)
                .lastSeenAt(now)
                .build();
    }
}
