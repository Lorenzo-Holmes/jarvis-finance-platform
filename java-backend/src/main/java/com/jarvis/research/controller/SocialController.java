package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.social.SocialDtos.GroupRequest;
import com.jarvis.research.social.SocialDtos.MessageRequest;
import com.jarvis.research.social.SocialDtos.PostRequest;
import com.jarvis.research.social.SocialDtos.ProfileUpdateRequest;
import com.jarvis.research.social.SocialService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/social")
@RequiredArgsConstructor
public class SocialController {
    private final SocialService socialService;

    @GetMapping("/profile")
    public ApiResponse<Object> profile() {
        Long userId = CurrentUser.id();
        return ApiResponse.ok(socialService.profile(userId, userId));
    }

    @PatchMapping("/profile")
    public ApiResponse<Object> updateProfile(@Valid @RequestBody ProfileUpdateRequest body,
                                             HttpServletRequest request) {
        return ApiResponse.ok(socialService.updateProfile(CurrentUser.id(), body, clientIp(request)), "资料已更新");
    }

    @GetMapping("/achievements")
    public ApiResponse<Object> achievements() {
        return ApiResponse.ok(socialService.achievements(CurrentUser.id()));
    }

    @GetMapping("/users")
    public ApiResponse<Object> users(@RequestParam(defaultValue = "") String query,
                                     @RequestParam(defaultValue = "0") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(socialService.searchUsers(CurrentUser.id(), query, page, size));
    }

    @GetMapping("/users/{userId}")
    public ApiResponse<Object> user(@PathVariable Long userId) {
        return ApiResponse.ok(socialService.profile(CurrentUser.id(), userId));
    }

    @GetMapping("/users/{userId}/activity")
    public ApiResponse<Object> activity(@PathVariable Long userId,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(socialService.activities(CurrentUser.id(), userId, page, size));
    }

    @GetMapping("/groups")
    public ApiResponse<Object> groups(@RequestParam(defaultValue = "") String query,
                                      @RequestParam(defaultValue = "0") int page,
                                      @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(socialService.groups(CurrentUser.id(), query, page, size));
    }

    @PostMapping("/groups")
    public ApiResponse<Object> createGroup(@Valid @RequestBody GroupRequest body, HttpServletRequest request) {
        return ApiResponse.ok(socialService.createGroup(CurrentUser.id(), body, clientIp(request)), "研究小组已创建");
    }

    @GetMapping("/groups/{groupId}")
    public ApiResponse<Object> group(@PathVariable Long groupId) {
        return ApiResponse.ok(socialService.group(CurrentUser.id(), groupId));
    }

    @PatchMapping("/groups/{groupId}")
    public ApiResponse<Object> updateGroup(@PathVariable Long groupId, @Valid @RequestBody GroupRequest body,
                                           HttpServletRequest request) {
        return ApiResponse.ok(socialService.updateGroup(CurrentUser.id(), groupId, body, clientIp(request)), "研究小组已更新");
    }

    @PostMapping("/groups/{groupId}/join")
    public ApiResponse<Object> join(@PathVariable Long groupId, HttpServletRequest request) {
        return ApiResponse.ok(socialService.joinGroup(CurrentUser.id(), groupId, clientIp(request)), "已加入研究小组");
    }

    @DeleteMapping("/groups/{groupId}/leave")
    public ApiResponse<Object> leave(@PathVariable Long groupId, HttpServletRequest request) {
        socialService.leaveGroup(CurrentUser.id(), groupId, clientIp(request));
        return ApiResponse.ok(Map.of("groupId", groupId), "已退出研究小组");
    }

    @PostMapping("/groups/{groupId}/members/{userId}")
    public ApiResponse<Object> addMember(@PathVariable Long groupId, @PathVariable Long userId,
                                         HttpServletRequest request) {
        return ApiResponse.ok(socialService.addMember(CurrentUser.id(), groupId, userId, clientIp(request)), "成员已加入");
    }

    @DeleteMapping("/groups/{groupId}/members/{userId}")
    public ApiResponse<Object> removeMember(@PathVariable Long groupId, @PathVariable Long userId,
                                            HttpServletRequest request) {
        socialService.removeMember(CurrentUser.id(), groupId, userId, clientIp(request));
        return ApiResponse.ok(Map.of("groupId", groupId, "userId", userId), "成员已移除");
    }

    @GetMapping("/feed")
    public ApiResponse<Object> feed(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(socialService.feed(CurrentUser.id(), page, size));
    }

    @PostMapping("/posts")
    public ApiResponse<Object> post(@Valid @RequestBody PostRequest body, HttpServletRequest request) {
        return ApiResponse.ok(socialService.createPublicPost(CurrentUser.id(), body, clientIp(request)), "动态已发布");
    }

    @GetMapping("/groups/{groupId}/posts")
    public ApiResponse<Object> groupPosts(@PathVariable Long groupId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(socialService.groupPosts(CurrentUser.id(), groupId, page, size));
    }

    @PostMapping("/groups/{groupId}/posts")
    public ApiResponse<Object> groupPost(@PathVariable Long groupId, @Valid @RequestBody PostRequest body,
                                         HttpServletRequest request) {
        return ApiResponse.ok(socialService.createGroupPost(CurrentUser.id(), groupId, body, clientIp(request)), "小组动态已发布");
    }

    @GetMapping("/messages/conversations")
    public ApiResponse<Object> conversations() {
        return ApiResponse.ok(socialService.conversations(CurrentUser.id()));
    }

    @GetMapping("/messages/unread-count")
    public ApiResponse<Object> unreadMessageCount() {
        return ApiResponse.ok(socialService.unreadMessageCount(CurrentUser.id()));
    }

    @GetMapping("/messages/{userId}")
    public ApiResponse<Object> thread(@PathVariable Long userId) {
        return ApiResponse.ok(socialService.thread(CurrentUser.id(), userId));
    }

    @PostMapping("/messages/{userId}")
    public ApiResponse<Object> sendMessage(@PathVariable Long userId, @Valid @RequestBody MessageRequest body,
                                           HttpServletRequest request) {
        return ApiResponse.ok(socialService.sendMessage(CurrentUser.id(), userId, body, clientIp(request)), "消息已发送");
    }

    private String clientIp(HttpServletRequest request) {
        String trustedProxyIp = request.getHeader("X-Real-IP");
        if (trustedProxyIp != null && !trustedProxyIp.isBlank()) return trustedProxyIp.trim();
        return request.getRemoteAddr();
    }
}
