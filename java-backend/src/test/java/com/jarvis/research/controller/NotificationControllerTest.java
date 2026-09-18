package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.notification.NotificationLevel;
import com.jarvis.research.notification.NotificationService;
import com.jarvis.research.notification.NotificationType;
import com.jarvis.research.notification.UserNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link NotificationController} 的接口形状。
 *
 * <p>钉两件事：<strong>引用要拆成嵌套对象</strong>（前端不该自己拼 `link_kind` + `link_ref`），
 * 以及 <strong>`read` 要给布尔值</strong>（前端读 `read_at` 是否为 null 可读性太差、也容易写错）。
 * 归属与权限不在这里测 —— 那是服务层与仓储层的事，控制器只做视图整形与委派。</p>
 */
class NotificationControllerTest {

    private static final long USER = 42L;
    private static final long NOTIFICATION_ID = 100L;

    private NotificationService service;
    private NotificationController controller;

    @BeforeEach
    void setUp() {
        service = mock(NotificationService.class);
        controller = new NotificationController(service);
        SecurityContextHolder.getContext().setAuthentication(
                new TestingAuthenticationToken(USER, null));
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listReturnsThePageEnvelopePlusTheUnreadCount() {
        when(service.list(eq(USER), eq(false), eq(0), eq(20)))
                .thenReturn(new PageImpl<>(List.of(notification()), PageRequest.of(0, 20), 1));
        when(service.unreadCount(USER)).thenReturn(3L);

        Map<String, Object> data = controller.list(false, 0, 20).getData();

        assertEquals(1L, data.get("total"));
        assertEquals(0, data.get("page"));
        assertEquals(20, data.get("size"));
        assertEquals(3L, data.get("unread"), "列表顺手带未读数，省前端一次请求");
        assertEquals(1, ((List<?>) data.get("items")).size());
    }

    @Test
    void listPassesTheUnreadOnlyFilterThrough() {
        when(service.list(eq(USER), eq(true), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        controller.list(true, 0, 20);

        verify(service).list(eq(USER), eq(true), eq(0), eq(20));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theViewNestsTheLinkAndExposesReadAsABoolean() {
        when(service.list(eq(USER), anyBoolean(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(notification()), PageRequest.of(0, 20), 1));

        Map<String, Object> item = (Map<String, Object>) ((List<?>) controller.list(false, 0, 20)
                .getData().get("items")).get(0);

        assertEquals("RISK", item.get("level"));
        assertEquals("RISK_ALERT", item.get("type"));
        assertEquals(3, item.get("repeat_count"), "合并次数要暴露出来，用户才知道故障还在持续");
        assertEquals(Boolean.FALSE, item.get("read"));
        Map<String, Object> link = (Map<String, Object>) item.get("link");
        assertEquals("SCHEDULED_TASK", link.get("kind"));
        assertEquals(9L, link.get("ref"));
    }

    @Test
    void unreadCountIsASingleNumber() {
        when(service.unreadCount(USER)).thenReturn(7L);

        assertEquals(7L, controller.unreadCount().getData().get("unread"));
    }

    @Test
    void markReadReturnsTheUpdatedView() {
        UserNotification read = notification();
        read.setReadAt(LocalDateTime.now());
        when(service.markRead(USER, NOTIFICATION_ID)).thenReturn(read);

        Map<String, Object> data = controller.markRead(NOTIFICATION_ID).getData();

        assertTrue((Boolean) data.get("read"));
        assertFalse(data.get("read_at") == null, "已读时间要保留，将来做「什么时候读的」用得上");
    }

    @Test
    void markAllReadReportsHowManyWereTouchedAndTheRemainingUnread() {
        when(service.markAllRead(USER)).thenReturn(4);
        when(service.unreadCount(USER)).thenReturn(0L);

        Map<String, Object> data = controller.markAllRead().getData();

        assertEquals(4, data.get("updated"));
        assertEquals(0L, data.get("unread"));
    }

    @Test
    void deleteConfirmsAndRefreshesTheUnreadCount() {
        when(service.unreadCount(USER)).thenReturn(2L);

        ApiResponse<Map<String, Object>> response = controller.delete(NOTIFICATION_ID);

        verify(service).delete(USER, NOTIFICATION_ID);
        assertEquals(Boolean.TRUE, response.getData().get("deleted"));
        assertEquals(2L, response.getData().get("unread"));
    }

    private static UserNotification notification() {
        return UserNotification.builder()
                .id(NOTIFICATION_ID)
                .userId(USER)
                .type(NotificationType.RISK_ALERT)
                .level(NotificationLevel.RISK)
                .title("风险检测命中：每日风险检测（维持担保比例 12.50%）")
                .body("风险检测命中：维持担保比例 12.50%（风险等级 DANGER）")
                .linkKind("SCHEDULED_TASK")
                .linkRef(9L)
                .dedupKey("42:RISK_ALERT:9")
                .repeatCount(3)
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .lastSeenAt(LocalDateTime.now().minusMinutes(1))
                .build();
    }
}
