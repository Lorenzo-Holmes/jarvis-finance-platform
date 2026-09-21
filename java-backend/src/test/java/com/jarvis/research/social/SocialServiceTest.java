package com.jarvis.research.social;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.social.SocialDtos.MessageRequest;
import com.jarvis.research.social.SocialDtos.ProfileUpdateRequest;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SocialServiceTest {
    private UserRepository userRepository;
    private CommunityGroupRepository groupRepository;
    private CommunityGroupMemberRepository memberRepository;
    private CommunityPostRepository postRepository;
    private DirectMessageRepository messageRepository;
    private UserActivityRepository activityRepository;
    private AchievementService achievementService;
    private AuditService auditService;
    private SocialService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        groupRepository = mock(CommunityGroupRepository.class);
        memberRepository = mock(CommunityGroupMemberRepository.class);
        postRepository = mock(CommunityPostRepository.class);
        messageRepository = mock(DirectMessageRepository.class);
        activityRepository = mock(UserActivityRepository.class);
        achievementService = mock(AchievementService.class);
        auditService = mock(AuditService.class);
        service = new SocialService(
                userRepository, groupRepository, memberRepository, postRepository,
                messageRepository, activityRepository, achievementService, auditService);
    }

    @Test
    void privateProfileDoesNotExposeAvatarSignatureContactOrAchievementsToOthers() {
        User target = user(2L);
        target.setAvatarUrl("https://example.test/avatar.png");
        target.setSignature("private signature");
        target.setContactInfo("private contact");
        target.setProfilePublic(false);
        target.setContactPublic(false);
        target.setActivityPublic(false);
        when(userRepository.findById(2L)).thenReturn(Optional.of(target));

        Map<String, Object> profile = service.profile(1L, 2L);

        assertEquals(2L, profile.get("id"));
        assertEquals("User 2", profile.get("displayName"));
        assertFalse(profile.containsKey("avatarUrl"));
        assertFalse(profile.containsKey("signature"));
        assertFalse(profile.containsKey("contactInfo"));
        assertEquals(java.util.List.of(), profile.get("achievements"));
        verifyNoInteractions(achievementService);
    }

    @Test
    void closedGroupCannotBeSelfJoined() {
        CommunityGroup group = CommunityGroup.builder()
                .id(8L).ownerUserId(9L).name("Closed Desk").visibility("CLOSED")
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        when(groupRepository.findById(8L)).thenReturn(Optional.of(group));
        when(memberRepository.existsByGroupIdAndUserId(8L, 2L)).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.joinGroup(2L, 8L, "127.0.0.1"));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(memberRepository, never()).save(any());
    }

    @Test
    void userCannotSendDirectMessageToSelf() {
        MessageRequest request = new MessageRequest();
        request.setContent("hello");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(5L, 5L, request, "127.0.0.1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(messageRepository);
    }

    @Test
    void disabledUserCannotReceiveNewDirectMessages() {
        User disabled = user(8L);
        disabled.setEnabled(false);
        when(userRepository.findById(8L)).thenReturn(Optional.of(disabled));
        MessageRequest request = new MessageRequest();
        request.setContent("hello");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(7L, 8L, request, "127.0.0.1"));

        assertEquals(HttpStatus.CONFLICT, error.getStatusCode());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void directMessageRejectsInvisibleControlCharactersBeforePersisting() {
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L)));
        MessageRequest request = new MessageRequest();
        request.setContent("hello\u0000world");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.sendMessage(7L, 8L, request, "127.0.0.1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(messageRepository, never()).save(any());
    }

    @Test
    void profileRejectsNonHttpsAvatarBeforePersisting() {
        User current = user(3L);
        when(userRepository.findById(3L)).thenReturn(Optional.of(current));
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setDisplayName("User 3");
        request.setAvatarUrl("http://example.test/avatar.png");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateProfile(3L, request, "127.0.0.1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(userRepository, never()).save(any());
    }

    @Test
    void feedUsesViewerAwareVisibilityQuerySoClosedMembershipCanBeIncluded() {
        when(postRepository.findVisibleFeed(eq(7L), any())).thenReturn(new PageImpl<>(java.util.List.of()));

        service.feed(7L, 0, 20);

        verify(postRepository).findVisibleFeed(eq(7L), any());
    }

    @Test
    void unreadMessageCountOnlyUsesUnreadMessagesAddressedToCurrentUser() {
        when(messageRepository.countByRecipientUserIdAndReadAtIsNull(7L)).thenReturn(4L);

        assertEquals(4L, service.unreadMessageCount(7L).get("count"));
    }

    @Test
    void groupSearchUsesCaseInsensitiveNameQueryWhenProvided() {
        when(groupRepository.findVisibleToUserByName(eq(7L), eq("gold"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.groups(7L, " gold ", 0, 20);

        verify(groupRepository).findVisibleToUserByName(eq(7L), eq("gold"), any(PageRequest.class));
    }

    @Test
    void groupDirectoryUsesViewerAwareVisibilityQuery() {
        when(groupRepository.findVisibleToUser(eq(7L), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.groups(7L, "", 0, 20);

        verify(groupRepository).findVisibleToUser(eq(7L), any(PageRequest.class));
    }

    @Test
    void groupDetailsBatchLoadMemberProfiles() {
        CommunityGroup group = CommunityGroup.builder().id(8L).ownerUserId(9L).name("Desk").visibility("OPEN").build();
        CommunityGroupMember member = CommunityGroupMember.builder().groupId(8L).userId(7L).role("MEMBER")
                .createdAt(LocalDateTime.now()).build();
        when(groupRepository.findById(8L)).thenReturn(Optional.of(group));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L)));
        when(memberRepository.findByGroupIdAndUserId(8L, 7L)).thenReturn(Optional.of(member));
        when(memberRepository.findByGroupIdOrderByCreatedAtAsc(8L)).thenReturn(java.util.List.of(member));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(user(7L)));

        service.group(7L, 8L);

        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(7L);
    }

    @Test
    void feedBatchLoadsPostAuthorsAndGroups() {
        CommunityPost first = CommunityPost.builder().id(1L).authorUserId(7L).content("a").createdAt(LocalDateTime.now()).build();
        CommunityPost second = CommunityPost.builder().id(2L).authorUserId(8L).groupId(3L).content("b").createdAt(LocalDateTime.now()).build();
        when(postRepository.findVisibleFeed(eq(9L), any())).thenReturn(new PageImpl<>(java.util.List.of(first, second)));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(user(7L), user(8L)));
        when(groupRepository.findAllById(any())).thenReturn(java.util.List.of(
                CommunityGroup.builder().id(3L).ownerUserId(8L).name("Desk").visibility("OPEN").build()));

        Map<String, Object> result = service.feed(9L, 0, 20);

        assertEquals(2, ((java.util.List<?>) result.get("items")).size());
        verify(userRepository, times(1)).findAllById(any());
        verify(groupRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(7L);
        verify(userRepository, never()).findById(8L);
    }

    @Test
    void groupDirectoryBatchLoadsCountsOwnersAndMemberships() {
        CommunityGroup first = CommunityGroup.builder().id(1L).ownerUserId(8L).name("A").visibility("OPEN").build();
        CommunityGroup second = CommunityGroup.builder().id(2L).ownerUserId(9L).name("B").visibility("OPEN").build();
        when(groupRepository.findVisibleToUser(eq(7L), any())).thenReturn(new PageImpl<>(java.util.List.of(first, second)));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(user(8L), user(9L)));
        when(memberRepository.findByGroupIdInAndUserId(any(), eq(7L))).thenReturn(java.util.List.of(
                CommunityGroupMember.builder().groupId(1L).userId(7L).role("MEMBER").build()));
        when(memberRepository.countByGroupIds(any())).thenReturn(java.util.List.of(new Object[]{1L, 3L}, new Object[]{2L, 5L}));
        when(postRepository.countByGroupIds(any())).thenReturn(java.util.List.of(new Object[]{1L, 4L}, new Object[]{2L, 6L}));

        Map<String, Object> result = service.groups(7L, "", 0, 20);

        assertEquals(2, ((java.util.List<?>) result.get("items")).size());
        verify(userRepository, times(1)).findAllById(any());
        verify(memberRepository, never()).countByGroupId(anyLong());
        verify(postRepository, never()).countByGroupId(anyLong());
        verify(memberRepository, never()).findByGroupIdAndUserId(anyLong(), eq(7L));
    }

    @Test
    void onlyOwnerCanEditGroup() {
        CommunityGroup group = CommunityGroup.builder().id(8L).ownerUserId(9L).name("A").visibility("OPEN").build();
        when(groupRepository.findById(8L)).thenReturn(Optional.of(group));
        SocialDtos.GroupRequest request = new SocialDtos.GroupRequest();
        request.setName("B");
        request.setVisibility("OPEN");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.updateGroup(7L, 8L, request, "127.0.0.1"));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
    }

    @Test
    void ownerCannotRemoveSelfFromMemberList() {
        CommunityGroup group = CommunityGroup.builder().id(8L).ownerUserId(9L).name("A").visibility("OPEN").build();
        when(groupRepository.findById(8L)).thenReturn(Optional.of(group));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.removeMember(9L, 8L, 9L, "127.0.0.1"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(memberRepository, never()).deleteByGroupIdAndUserId(anyLong(), anyLong());
    }

    @Test
    void userCannotDeleteAnotherUsersPost() {
        CommunityPost post = CommunityPost.builder().id(12L).authorUserId(9L).content("x").build();
        when(postRepository.findById(12L)).thenReturn(Optional.of(post));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.deletePost(7L, 12L, "127.0.0.1"));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatusCode());
        verify(postRepository, never()).delete(any());
    }

    @Test
    void deletingOwnPostAlsoRemovesItsActivityReference() {
        CommunityPost post = CommunityPost.builder().id(12L).authorUserId(7L).content("x").build();
        when(postRepository.findById(12L)).thenReturn(Optional.of(post));

        service.deletePost(7L, 12L, "127.0.0.1");

        verify(activityRepository).deleteByUserIdAndReferenceTypeAndReferenceId(7L, "POST", "12");
        verify(postRepository).delete(post);
    }

    @Test
    void conversationsBatchLoadPartnerProfilesInsteadOfNPlusOneLookups() {
        DirectMessage first = DirectMessage.builder()
                .id(1L).senderUserId(7L).recipientUserId(8L).content("a")
                .createdAt(LocalDateTime.now()).build();
        DirectMessage second = DirectMessage.builder()
                .id(2L).senderUserId(9L).recipientUserId(7L).content("b")
                .createdAt(LocalDateTime.now().minusMinutes(1)).build();
        when(messageRepository.findRecentForUser(eq(7L), any())).thenReturn(java.util.List.of(first, second));
        when(userRepository.findAllById(any())).thenReturn(java.util.List.of(user(8L), user(9L)));

        java.util.List<Map<String, Object>> rows = service.conversations(7L);

        assertEquals(2, rows.size());
        verify(userRepository, times(1)).findAllById(any());
        verify(userRepository, never()).findById(8L);
        verify(userRepository, never()).findById(9L);
    }

    @Test
    void invitingExistingMemberIsIdempotentAndDoesNotEmitActivity() {
        CommunityGroup group = CommunityGroup.builder().id(8L).ownerUserId(9L).name("Desk").visibility("OPEN").build();
        when(groupRepository.findById(8L)).thenReturn(Optional.of(group));
        when(userRepository.findById(7L)).thenReturn(Optional.of(user(7L)));
        when(userRepository.findById(9L)).thenReturn(Optional.of(user(9L)));
        when(memberRepository.existsByGroupIdAndUserId(8L, 7L)).thenReturn(true);
        when(memberRepository.findByGroupIdAndUserId(8L, 9L)).thenReturn(Optional.of(
                CommunityGroupMember.builder().groupId(8L).userId(9L).role("OWNER").build()));
        when(memberRepository.findByGroupIdOrderByCreatedAtAsc(8L)).thenReturn(java.util.List.of());

        service.addMember(9L, 8L, 7L, "127.0.0.1");

        verify(memberRepository, never()).save(any());
        verify(activityRepository, never()).save(any());
        verify(achievementService, never()).evaluateSocial(7L);
    }

    @Test
    void postReferenceMustBeCompletePair() {
        SocialDtos.PostRequest request = new SocialDtos.PostRequest();
        request.setContent("research note");
        request.setReferenceType("RESEARCH_TASK");

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.createPublicPost(7L, request, "127.0.0.1"));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verify(postRepository, never()).save(any());
    }

    @Test
    void savingIdenticalProfileIsNoOp() {
        User current = user(7L);
        current.setAvatarUrl("https://example.test/a.png");
        current.setSignature("sig");
        current.setContactInfo("contact");
        current.setProfilePublic(true);
        current.setContactPublic(false);
        current.setActivityPublic(true);
        when(userRepository.findById(7L)).thenReturn(Optional.of(current));
        when(achievementService.overview(7L)).thenReturn(Map.of("items", java.util.List.of()));
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setDisplayName(current.getDisplayName());
        request.setAvatarUrl(current.getAvatarUrl());
        request.setSignature(current.getSignature());
        request.setContactInfo(current.getContactInfo());
        request.setProfilePublic(true);
        request.setContactPublic(false);
        request.setActivityPublic(true);

        service.updateProfile(7L, request, "127.0.0.1");

        verify(userRepository, never()).save(any());
        verify(activityRepository, never()).save(any());
        verify(auditService, never()).record(eq(7L), eq("SOCIAL_PROFILE_UPDATE"), anyString(), anyString(), anyString());
        verify(achievementService, never()).evaluateSocial(7L);
    }

    @Test
    void messageThreadReturnsPagedEnvelopeWithChronologicalItems() {
        DirectMessage newer = DirectMessage.builder().id(2L).senderUserId(7L).recipientUserId(8L)
                .content("new").createdAt(LocalDateTime.now()).build();
        DirectMessage older = DirectMessage.builder().id(1L).senderUserId(8L).recipientUserId(7L)
                .content("old").createdAt(LocalDateTime.now().minusMinutes(1)).build();
        when(userRepository.findById(8L)).thenReturn(Optional.of(user(8L)));
        when(messageRepository.findThread(eq(7L), eq(8L), any())).thenReturn(new PageImpl<>(
                java.util.List.of(newer, older), PageRequest.of(0, 40), 41));

        Map<String, Object> result = service.thread(7L, 8L, 0, 40);
        java.util.List<?> items = (java.util.List<?>) result.get("items");

        assertEquals(2, items.size());
        assertEquals("old", ((Map<?, ?>) items.get(0)).get("content"));
        assertEquals(2, result.get("totalPages"));
    }

    @Test
    void userDiscoveryOnlyQueriesEnabledAccounts() {
        when(userRepository.findByEnabledTrue(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));
        when(userRepository.findByEnabledTrueAndDisplayNameContainingIgnoreCase(eq("alice"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.searchUsers(7L, "", 0, 20);
        service.searchUsers(7L, " alice ", 0, 20);

        verify(userRepository).findByEnabledTrue(any(PageRequest.class));
        verify(userRepository).findByEnabledTrueAndDisplayNameContainingIgnoreCase(eq("alice"), any(PageRequest.class));
        verify(userRepository, never()).findAll(any(PageRequest.class));
    }

    private static User user(Long id) {
        return User.builder()
                .id(id)
                .email("user" + id + "@example.test")
                .passwordHash("hash")
                .displayName("User " + id)
                .enabled(true)
                .profilePublic(true)
                .contactPublic(false)
                .activityPublic(true)
                .createdAt(LocalDateTime.now())
                .build();
    }
}
