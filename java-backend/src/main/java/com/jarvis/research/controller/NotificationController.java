package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.notification.NotificationService;
import com.jarvis.research.notification.UserNotification;
import com.jarvis.research.security.CurrentUser;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知：列表、未读数、标记已读、删除。
 *
 * <p>对应 PRD V1.2 增量需求 3「用户定时任务与任务管理系统」→ 通知机制（Web 站内通知）。</p>
 *
 * <p><strong>刻意不挂 featureKey</strong>（与 {@code ScheduledTaskController} 的取舍不同）：
 * 通知是"系统告诉我发生了什么"，属于账号自带的读取能力。而权限体系是
 * 「配过任一权限即按白名单、未配则全放行」的混合制 —— 一旦给通知也加 key，
 * 就会出现"管理员给某人配了权限之后，他连自己的通知都看不到"这种反直觉的后果，
 * 且通知里可能正是"你的任务挂了"这类必须送达的内容。归属校验已足够。</p>
 */
@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(name = "unread_only", defaultValue = "false")
                                                 boolean unreadOnly,
                                                 @RequestParam(defaultValue = "0") int page,
                                                 @RequestParam(defaultValue = "20") int size) {
        Page<UserNotification> notifications = notificationService.list(CurrentUser.id(), unreadOnly, page, size);
        List<Map<String, Object>> items = new ArrayList<>();
        for (UserNotification notification : notifications.getContent()) {
            items.add(view(notification));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("items", items);
        payload.put("total", notifications.getTotalElements());
        payload.put("page", notifications.getNumber());
        payload.put("size", notifications.getSize());
        payload.put("unread", notificationService.unreadCount(CurrentUser.id()));
        return ApiResponse.ok(payload);
    }

    /** 未读数，供前端角标轮询（15s 足够：通知是任务级事件，不是秒级行情）。 */
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("unread", notificationService.unreadCount(CurrentUser.id()));
        return ApiResponse.ok(payload);
    }

    @PostMapping("/{id}/read")
    public ApiResponse<Map<String, Object>> markRead(@PathVariable Long id) {
        return ApiResponse.ok(view(notificationService.markRead(CurrentUser.id(), id)));
    }

    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead() {
        int updated = notificationService.markAllRead(CurrentUser.id());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("updated", updated);
        payload.put("unread", notificationService.unreadCount(CurrentUser.id()));
        return ApiResponse.ok(payload);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Map<String, Object>> delete(@PathVariable Long id) {
        notificationService.delete(CurrentUser.id(), id);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deleted", true);
        // 删除后未读数会变，顺手带上，省前端一次轮询。
        payload.put("unread", notificationService.unreadCount(CurrentUser.id()));
        return ApiResponse.ok(payload);
    }

    /**
     * 视图：引用拆成嵌套对象，前端不必自己拼 `link_kind` + `link_ref` 两个字段。
     *
     * <p>{@code read} 是给前端直接用的布尔值（读 null 判断可读性差），
     * 同时保留 {@code read_at} 以便将来做"什么时候读的"。</p>
     */
    private Map<String, Object> view(UserNotification notification) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", notification.getId());
        view.put("type", notification.getType() == null ? null : notification.getType().name());
        view.put("level", notification.getLevel() == null ? null : notification.getLevel().name());
        view.put("title", notification.getTitle());
        view.put("body", notification.getBody());

        Map<String, Object> link = new LinkedHashMap<>();
        link.put("kind", notification.getLinkKind());
        link.put("ref", notification.getLinkRef());
        view.put("link", link);

        view.put("repeat_count", notification.getRepeatCount());
        view.put("read", notification.getReadAt() != null);
        view.put("read_at", notification.getReadAt());
        view.put("created_at", notification.getCreatedAt());
        view.put("last_seen_at", notification.getLastSeenAt());
        return view;
    }
}
