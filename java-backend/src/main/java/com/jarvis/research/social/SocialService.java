package com.jarvis.research.social;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.social.SocialDtos.GroupRequest;
import com.jarvis.research.social.SocialDtos.MessageRequest;
import com.jarvis.research.social.SocialDtos.PostRequest;
import com.jarvis.research.social.SocialDtos.ProfileUpdateRequest;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SocialService {
    private static final int MAX_PAGE_SIZE = 50;

    private final UserRepository userRepository;
    private final CommunityGroupRepository groupRepository;
    private final CommunityGroupMemberRepository memberRepository;
    private final CommunityPostRepository postRepository;
    private final DirectMessageRepository messageRepository;
    private final UserActivityRepository activityRepository;
    private final AchievementService achievementService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Map<String, Object> searchUsers(Long viewerId, String query, int page, int size) {
        PageRequest request = pageRequest(page, size);
        Page<User> users = query == null || query.isBlank()
                ? userRepository.findAll(request)
                : userRepository.findByDisplayNameContainingIgnoreCase(query.trim(), request);
        return pageView(users.map(user -> publicUser(viewerId, user)));
    }

    @Transactional
    public Map<String, Object> profile(Long viewerId, Long targetUserId) {
        User user = requireUser(targetUserId);
        boolean self = Objects.equals(viewerId, targetUserId);
        Map<String, Object> out = new LinkedHashMap<>(publicUser(viewerId, user));
        out.put("self", self);
        if (self) {
            out.put("email", user.getEmail());
            out.put("contactInfo", user.getContactInfo());
            out.put("profilePublic", user.isProfilePublic());
            out.put("contactPublic", user.isContactPublic());
            out.put("activityPublic", user.isActivityPublic());
        }
        if (self || user.isActivityPublic()) {
            out.put("achievements", self
                    ? ((List<?>) achievementService.overview(targetUserId).get("items")).stream()
                        .filter(item -> item instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("unlocked")))
                        .toList()
                    : achievementService.persistedForPublicProfile(targetUserId));
        } else {
            out.put("achievements", List.of());
        }
        return out;
    }

    @Transactional
    public Map<String, Object> updateProfile(Long userId, ProfileUpdateRequest request, String clientIp) {
        User user = requireUser(userId);
        String displayName = request.getDisplayName().trim();
        String avatarUrl = trimToNull(request.getAvatarUrl());
        String signature = trimToNull(request.getSignature());
        String contactInfo = trimToNull(request.getContactInfo());
        if (avatarUrl != null && !avatarUrl.matches("(?i)^https://[^\\s]{1,492}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "头像地址必须使用 HTTPS");
        }
        boolean unchanged = Objects.equals(user.getDisplayName(), displayName)
                && Objects.equals(user.getAvatarUrl(), avatarUrl)
                && Objects.equals(user.getSignature(), signature)
                && Objects.equals(user.getContactInfo(), contactInfo)
                && user.isProfilePublic() == request.isProfilePublic()
                && user.isContactPublic() == request.isContactPublic()
                && user.isActivityPublic() == request.isActivityPublic();
        if (unchanged) return profile(userId, userId);
        user.setDisplayName(displayName);
        user.setAvatarUrl(avatarUrl);
        user.setSignature(signature);
        user.setContactInfo(contactInfo);
        user.setProfilePublic(request.isProfilePublic());
        user.setContactPublic(request.isContactPublic());
        user.setActivityPublic(request.isActivityPublic());
        userRepository.save(user);
        auditService.record(userId, "SOCIAL_PROFILE_UPDATE", "profile:" + userId, clientIp, "更新个人资料与隐私设置");
        addActivity(userId, "PROFILE_UPDATED", "更新了个人资料", "PROFILE", String.valueOf(userId));
        achievementService.evaluateSocial(userId);
        return profile(userId, userId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> activities(Long viewerId, Long targetUserId, int page, int size) {
        User user = requireUser(targetUserId);
        if (!Objects.equals(viewerId, targetUserId) && !user.isActivityPublic()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该用户未公开动态");
        }
        return pageView(activityRepository.findByUserIdOrderByCreatedAtDesc(targetUserId, pageRequest(page, size))
                .map(this::activityView));
    }

    @Transactional(readOnly = true)
    public Map<String, Object> groups(Long viewerId, String query, int page, int size) {
        PageRequest request = pageRequest(page, size);
        Page<CommunityGroup> groups = query == null || query.isBlank()
                ? groupRepository.findVisibleToUser(viewerId, request)
                : groupRepository.findVisibleToUserByName(viewerId, query.trim(), request);
        return groupPageView(viewerId, groups);
    }

    @Transactional
    public Map<String, Object> createGroup(Long userId, GroupRequest request, String clientIp) {
        CommunityGroup group = groupRepository.save(CommunityGroup.builder()
                .ownerUserId(userId)
                .name(request.getName().trim())
                .description(trimToNull(request.getDescription()))
                .visibility(request.getVisibility() == null ? "OPEN" : request.getVisibility().trim().toUpperCase())
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build());
        memberRepository.save(CommunityGroupMember.builder()
                .groupId(group.getId()).userId(userId).role("OWNER").createdAt(LocalDateTime.now()).build());
        addActivity(userId, "GROUP_JOINED", "创建研究小组「" + group.getName() + "」", "GROUP", String.valueOf(group.getId()));
        auditService.record(userId, "COMMUNITY_GROUP_CREATE", "group:" + group.getId(), clientIp, group.getName());
        achievementService.evaluateSocial(userId);
        return groupView(userId, group, true);
    }

    @Transactional
    public Map<String, Object> updateGroup(Long userId, Long groupId, GroupRequest request, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        requireOwner(userId, group);
        group.setName(request.getName().trim());
        group.setDescription(trimToNull(request.getDescription()));
        group.setVisibility(request.getVisibility() == null ? "OPEN" : request.getVisibility().trim().toUpperCase());
        groupRepository.save(group);
        auditService.record(userId, "COMMUNITY_GROUP_UPDATE", "group:" + groupId, clientIp, group.getName());
        return groupView(userId, group, true);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> group(Long viewerId, Long groupId) {
        return groupView(viewerId, requireGroup(groupId), true);
    }

    @Transactional
    public Map<String, Object> joinGroup(Long userId, Long groupId, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        if (memberRepository.existsByGroupIdAndUserId(groupId, userId)) return groupView(userId, group, true);
        if (!"OPEN".equals(group.getVisibility())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该小组仅允许组主邀请加入");
        }
        saveMember(groupId, userId, "MEMBER");
        addActivity(userId, "GROUP_JOINED", "加入研究小组「" + group.getName() + "」", "GROUP", String.valueOf(groupId));
        auditService.record(userId, "COMMUNITY_GROUP_JOIN", "group:" + groupId, clientIp, group.getName());
        achievementService.evaluateSocial(userId);
        return groupView(userId, group, true);
    }

    @Transactional
    public Map<String, Object> addMember(Long ownerId, Long groupId, Long userId, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        requireOwner(ownerId, group);
        requireUser(userId);
        if (memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            return groupView(ownerId, group, true);
        }
        saveMember(groupId, userId, "MEMBER");
        addActivity(userId, "GROUP_JOINED", "加入研究小组「" + group.getName() + "」", "GROUP", String.valueOf(groupId));
        auditService.record(ownerId, "COMMUNITY_GROUP_MEMBER_ADD", "group:" + groupId, clientIp, "user=" + userId);
        achievementService.evaluateSocial(userId);
        return groupView(ownerId, group, true);
    }

    @Transactional
    public void removeMember(Long ownerId, Long groupId, Long userId, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        requireOwner(ownerId, group);
        if (Objects.equals(group.getOwnerUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能移除小组 OWNER");
        }
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) return;
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
        auditService.record(ownerId, "COMMUNITY_GROUP_MEMBER_REMOVE", "group:" + groupId, clientIp, "user=" + userId);
    }

    @Transactional
    public void leaveGroup(Long userId, Long groupId, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        if (Objects.equals(group.getOwnerUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "组主不能直接退出自己创建的小组");
        }
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) return;
        memberRepository.deleteByGroupIdAndUserId(groupId, userId);
        auditService.record(userId, "COMMUNITY_GROUP_LEAVE", "group:" + groupId, clientIp, group.getName());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> feed(Long viewerId, int page, int size) {
        return postPageView(viewerId, postRepository.findVisibleFeed(viewerId, pageRequest(page, size)));
    }

    @Transactional
    public Map<String, Object> createPublicPost(Long userId, PostRequest request, String clientIp) {
        return createPost(userId, null, request, clientIp);
    }

    @Transactional
    public void deletePost(Long userId, Long postId, String clientIp) {
        CommunityPost post = postRepository.findById(postId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "动态不存在"));
        if (!Objects.equals(post.getAuthorUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只能删除自己发布的动态");
        }
        postRepository.delete(post);
        auditService.record(userId, "COMMUNITY_POST_DELETE", "post:" + postId, clientIp, "deleted");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> groupPosts(Long viewerId, Long groupId, int page, int size) {
        CommunityGroup group = requireGroup(groupId);
        requireGroupRead(viewerId, group);
        return postPageView(viewerId,
                postRepository.findByGroupIdOrderByCreatedAtDesc(groupId, pageRequest(page, size)));
    }

    @Transactional
    public Map<String, Object> createGroupPost(Long userId, Long groupId, PostRequest request, String clientIp) {
        CommunityGroup group = requireGroup(groupId);
        if (!memberRepository.existsByGroupIdAndUserId(groupId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "加入小组后才能发布内容");
        }
        return createPost(userId, groupId, request, clientIp);
    }

    @Transactional
    public Map<String, Object> sendMessage(Long senderId, Long recipientId, MessageRequest request, String clientIp) {
        if (Objects.equals(senderId, recipientId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能给自己发送私信");
        }
        requireUser(recipientId);
        DirectMessage message = messageRepository.save(DirectMessage.builder()
                .senderUserId(senderId).recipientUserId(recipientId).content(request.getContent().trim())
                .createdAt(LocalDateTime.now()).build());
        auditService.record(senderId, "DIRECT_MESSAGE_SEND", "user:" + recipientId, clientIp, "message=" + message.getId());
        achievementService.evaluateSocial(senderId);
        return messageView(senderId, message);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> conversations(Long userId) {
        List<DirectMessage> messages = messageRepository.findRecentForUser(userId, PageRequest.of(0, 300));
        Map<Long, DirectMessage> latest = new LinkedHashMap<>();
        Map<Long, Integer> unread = new HashMap<>();
        for (DirectMessage message : messages) {
            Long partner = Objects.equals(message.getSenderUserId(), userId)
                    ? message.getRecipientUserId() : message.getSenderUserId();
            latest.putIfAbsent(partner, message);
            if (Objects.equals(message.getRecipientUserId(), userId) && message.getReadAt() == null) {
                unread.merge(partner, 1, Integer::sum);
            }
        }
        Map<Long, User> partners = new HashMap<>();
        userRepository.findAllById(latest.keySet()).forEach(user -> partners.put(user.getId(), user));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<Long, DirectMessage> entry : latest.entrySet()) {
            User partner = partners.get(entry.getKey());
            if (partner == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partner", publicUser(userId, partner));
            row.put("lastMessage", messageView(userId, entry.getValue()));
            row.put("unreadCount", unread.getOrDefault(entry.getKey(), 0));
            out.add(row);
        }
        return out;
    }

    @Transactional
    public List<Map<String, Object>> thread(Long userId, Long otherUserId) {
        requireUser(otherUserId);
        messageRepository.markThreadRead(userId, otherUserId, LocalDateTime.now());
        List<DirectMessage> rows = new ArrayList<>(
                messageRepository.findThread(userId, otherUserId, PageRequest.of(0, 200)));
        Collections.reverse(rows);
        return rows.stream().map(message -> messageView(userId, message)).toList();
    }

    public Map<String, Object> achievements(Long userId) { return achievementService.overview(userId); }

    public Map<String, Object> unreadMessageCount(Long userId) {
        return Map.of("count", messageRepository.countByRecipientUserIdAndReadAtIsNull(userId));
    }

    private Map<String, Object> createPost(Long userId, Long groupId, PostRequest request, String clientIp) {
        String referenceType = trimToNull(request.getReferenceType());
        String referenceId = trimToNull(request.getReferenceId());
        if ((referenceType == null) != (referenceId == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "referenceType 与 referenceId 必须同时提供");
        }
        CommunityPost post = postRepository.save(CommunityPost.builder()
                .authorUserId(userId).groupId(groupId).content(request.getContent().trim())
                .referenceType(referenceType).referenceId(referenceId)
                .createdAt(LocalDateTime.now()).build());
        String summary = "发布研究动态：" + abbreviate(post.getContent(), 120);
        addActivity(userId, "POST_CREATED", summary, "POST", String.valueOf(post.getId()));
        auditService.record(userId, "COMMUNITY_POST_CREATE", "post:" + post.getId(), clientIp,
                groupId == null ? "public" : "group=" + groupId);
        achievementService.evaluateSocial(userId);
        return postView(userId, post);
    }

    private Map<String, Object> groupView(Long viewerId, CommunityGroup group, boolean includeMembers) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", group.getId()); out.put("name", group.getName()); out.put("description", group.getDescription());
        out.put("visibility", group.getVisibility()); out.put("createdAt", group.getCreatedAt());
        out.put("updatedAt", group.getUpdatedAt());
        out.put("memberCount", memberRepository.countByGroupId(group.getId()));
        out.put("postCount", postRepository.countByGroupId(group.getId()));
        out.put("owner", publicUser(viewerId, requireUser(group.getOwnerUserId())));
        CommunityGroupMember membership = memberRepository.findByGroupIdAndUserId(group.getId(), viewerId).orElse(null);
        out.put("joined", membership != null);
        out.put("role", membership == null ? null : membership.getRole());
        if (includeMembers && ("OPEN".equals(group.getVisibility()) || membership != null)) {
            List<CommunityGroupMember> members = memberRepository.findByGroupIdOrderByCreatedAtAsc(group.getId())
                    .stream().limit(50).toList();
            Map<Long, User> users = new HashMap<>();
            userRepository.findAllById(members.stream().map(CommunityGroupMember::getUserId).toList())
                    .forEach(user -> users.put(user.getId(), user));
            out.put("members", members.stream()
                    .filter(member -> users.containsKey(member.getUserId()))
                    .map(member -> Map.of(
                            "role", member.getRole(),
                            "joinedAt", member.getCreatedAt(),
                            "user", publicUser(viewerId, users.get(member.getUserId()))
                    )).toList());
        } else out.put("members", List.of());
        return out;
    }

    private Map<String, Object> groupPageView(Long viewerId, Page<CommunityGroup> page) {
        List<CommunityGroup> groups = page.getContent();
        if (groups.isEmpty()) return pageView(page, List.of());
        List<Long> groupIds = groups.stream().map(CommunityGroup::getId).toList();
        Set<Long> ownerIds = groups.stream().map(CommunityGroup::getOwnerUserId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<Long, User> owners = new HashMap<>();
        userRepository.findAllById(ownerIds).forEach(user -> owners.put(user.getId(), user));
        Map<Long, CommunityGroupMember> memberships = new HashMap<>();
        memberRepository.findByGroupIdInAndUserId(groupIds, viewerId)
                .forEach(member -> memberships.put(member.getGroupId(), member));
        Map<Long, Long> memberCounts = countMap(memberRepository.countByGroupIds(groupIds));
        Map<Long, Long> postCounts = countMap(postRepository.countByGroupIds(groupIds));
        List<Map<String, Object>> items = groups.stream().map(group -> {
            CommunityGroupMember membership = memberships.get(group.getId());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", group.getId()); out.put("name", group.getName()); out.put("description", group.getDescription());
            out.put("visibility", group.getVisibility()); out.put("createdAt", group.getCreatedAt()); out.put("updatedAt", group.getUpdatedAt());
            out.put("memberCount", memberCounts.getOrDefault(group.getId(), 0L));
            out.put("postCount", postCounts.getOrDefault(group.getId(), 0L));
            User owner = owners.get(group.getOwnerUserId());
            if (owner != null) out.put("owner", publicUser(viewerId, owner));
            out.put("joined", membership != null);
            out.put("role", membership == null ? null : membership.getRole());
            out.put("members", List.of());
            return out;
        }).toList();
        return pageView(page, items);
    }

    private static Map<Long, Long> countMap(List<Object[]> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (Object[] row : rows) {
            if (row != null && row.length >= 2 && row[0] instanceof Number id && row[1] instanceof Number count) {
                out.put(id.longValue(), count.longValue());
            }
        }
        return out;
    }

    private Map<String, Object> postView(Long viewerId, CommunityPost post) {
        User author = requireUser(post.getAuthorUserId());
        CommunityGroup group = post.getGroupId() == null ? null : groupRepository.findById(post.getGroupId()).orElse(null);
        return postView(viewerId, post, author, group);
    }

    private Map<String, Object> postView(Long viewerId, CommunityPost post, User author, CommunityGroup group) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", post.getId()); out.put("content", post.getContent()); out.put("groupId", post.getGroupId());
        out.put("referenceType", post.getReferenceType()); out.put("referenceId", post.getReferenceId());
        out.put("createdAt", post.getCreatedAt());
        out.put("mine", Objects.equals(viewerId, post.getAuthorUserId()));
        out.put("author", publicUser(viewerId, author));
        if (group != null) out.put("group", Map.of(
                "id", group.getId(), "name", group.getName(), "visibility", group.getVisibility()));
        return out;
    }

    private Map<String, Object> postPageView(Long viewerId, Page<CommunityPost> page) {
        List<CommunityPost> posts = page.getContent();
        Set<Long> authorIds = new LinkedHashSet<>();
        Set<Long> groupIds = new LinkedHashSet<>();
        for (CommunityPost post : posts) {
            authorIds.add(post.getAuthorUserId());
            if (post.getGroupId() != null) groupIds.add(post.getGroupId());
        }
        Map<Long, User> authors = new HashMap<>();
        userRepository.findAllById(authorIds).forEach(user -> authors.put(user.getId(), user));
        Map<Long, CommunityGroup> groups = new HashMap<>();
        groupRepository.findAllById(groupIds).forEach(group -> groups.put(group.getId(), group));
        List<Map<String, Object>> items = posts.stream()
                .filter(post -> authors.containsKey(post.getAuthorUserId()))
                .map(post -> postView(viewerId, post, authors.get(post.getAuthorUserId()), groups.get(post.getGroupId())))
                .toList();
        return pageView(page, items);
    }

    private Map<String, Object> messageView(Long viewerId, DirectMessage message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", message.getId()); out.put("senderUserId", message.getSenderUserId());
        out.put("recipientUserId", message.getRecipientUserId()); out.put("content", message.getContent());
        out.put("createdAt", message.getCreatedAt()); out.put("readAt", message.getReadAt());
        out.put("mine", Objects.equals(viewerId, message.getSenderUserId()));
        return out;
    }

    private Map<String, Object> publicUser(Long viewerId, User user) {
        boolean self = Objects.equals(viewerId, user.getId());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", user.getId()); out.put("displayName", user.getDisplayName()); out.put("profilePublic", user.isProfilePublic());
        if (self || user.isProfilePublic()) {
            out.put("avatarUrl", user.getAvatarUrl()); out.put("signature", user.getSignature());
        }
        if (self || user.isContactPublic()) out.put("contactInfo", user.getContactInfo());
        out.put("contactPublic", user.isContactPublic()); out.put("activityPublic", user.isActivityPublic());
        return out;
    }

    private Map<String, Object> activityView(UserActivity activity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", activity.getId()); out.put("type", activity.getActivityType());
        out.put("summary", activity.getSummary()); out.put("referenceType", activity.getReferenceType());
        out.put("referenceId", activity.getReferenceId()); out.put("createdAt", activity.getCreatedAt());
        return out;
    }

    private void addActivity(Long userId, String type, String summary, String refType, String refId) {
        activityRepository.save(UserActivity.builder().userId(userId).activityType(type)
                .summary(abbreviate(summary, 500)).referenceType(refType).referenceId(refId)
                .createdAt(LocalDateTime.now()).build());
    }

    private void saveMember(Long groupId, Long userId, String role) {
        memberRepository.save(CommunityGroupMember.builder().groupId(groupId).userId(userId)
                .role(role).createdAt(LocalDateTime.now()).build());
    }

    private void requireOwner(Long userId, CommunityGroup group) {
        if (!Objects.equals(group.getOwnerUserId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "只有组主可以执行该操作");
        }
    }

    private void requireGroupRead(Long userId, CommunityGroup group) {
        if ("OPEN".equals(group.getVisibility())) return;
        if (!memberRepository.existsByGroupIdAndUserId(group.getId(), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该小组内容仅成员可见");
        }
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
    }

    private CommunityGroup requireGroup(Long groupId) {
        return groupRepository.findById(groupId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "研究小组不存在"));
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.min(MAX_PAGE_SIZE, Math.max(1, size)));
    }

    private static Map<String, Object> pageView(Page<?> page) {
        return pageView(page, page.getContent());
    }

    private static Map<String, Object> pageView(Page<?> page, List<?> items) {
        return Map.of(
                "items", items, "page", page.getNumber(), "size", page.getSize(),
                "totalElements", page.getTotalElements(), "totalPages", page.getTotalPages());
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String abbreviate(String value, int max) {
        if (value == null) return "";
        String text = value.trim().replaceAll("\\s+", " ");
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
