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
        when(groupRepository.findByNameContainingIgnoreCaseOrderByUpdatedAtDesc(eq("gold"), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(java.util.List.of()));

        service.groups(7L, " gold ", 0, 20);

        verify(groupRepository).findByNameContainingIgnoreCaseOrderByUpdatedAtDesc(eq("gold"), any(PageRequest.class));
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
