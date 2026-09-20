package com.jarvis.research.service;

import com.jarvis.research.user.GroupFeaturePermission;
import com.jarvis.research.user.GroupFeaturePermissionRepository;
import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMember;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeaturePermissionServiceGroupTest {

    private final UserFeaturePermissionRepository userPermissions = mock(UserFeaturePermissionRepository.class);
    private final UserGroupMemberRepository members = mock(UserGroupMemberRepository.class);
    private final UserGroupRepository groups = mock(UserGroupRepository.class);
    private final GroupFeaturePermissionRepository groupPermissions = mock(GroupFeaturePermissionRepository.class);
    private final FeaturePermissionService service = new FeaturePermissionService(
            userPermissions, members, groups, groupPermissions);

    @Test
    void enabledGroupFeatureIsInheritedByUserWithoutUserOverride() {
        when(userPermissions.countByUserId(7L)).thenReturn(0L);
        when(members.findByUserId(7L)).thenReturn(Optional.of(UserGroupMember.builder()
                .groupId(2L).userId(7L).createdAt(LocalDateTime.now()).build()));
        when(groups.findById(2L)).thenReturn(Optional.of(UserGroup.builder()
                .id(2L).name("research").enabled(true).build()));
        when(groupPermissions.countByGroupId(2L)).thenReturn(1L);
        when(groupPermissions.findByGroupIdAndFeatureKey(2L, "AI_REPORT"))
                .thenReturn(Optional.of(GroupFeaturePermission.builder()
                        .groupId(2L).featureKey("AI_REPORT").enabled(true).build()));

        assertDoesNotThrow(() -> service.require(7L, "AI_REPORT"));
    }

    @Test
    void userPermissionOverrideTakesPrecedenceOverGroup() {
        when(userPermissions.countByUserId(7L)).thenReturn(1L);
        when(userPermissions.findByUserIdAndFeatureKey(7L, "AI_REPORT"))
                .thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () -> service.require(7L, "AI_REPORT"));
    }
}
