package com.jarvis.research.service;

import com.jarvis.research.user.GroupFeaturePermission;
import com.jarvis.research.user.GroupFeaturePermissionRepository;
import com.jarvis.research.user.UserFeaturePermission;
import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMember;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link FeaturePermissionService#allows} —— 不抛异常的权限探测。
 *
 * <p>存在意义：前端要在用户<strong>点下去之前</strong>就把「新建 / 编辑 / 立即执行 / 恢复」置灰，
 * 而 {@code require} 只会抛 403，拿不到"能不能"。两者必须是同一套判定，
 * 否则会出现「按钮亮着但点了必失败」这种最糟的组合。</p>
 *
 * <p>这里同时钉住 {@code allows} 与 {@code require} 的<strong>一致性</strong>：
 * 同一组打桩下，{@code require} 抛异常的场合 {@code allows} 必须为 false，反之亦然。</p>
 */
class FeaturePermissionServiceAllowsTest {

    private static final long USER = 7L;
    private static final long GROUP = 2L;
    private static final String KEY = "TASK_MANAGE";

    private final UserFeaturePermissionRepository userPermissions =
            mock(UserFeaturePermissionRepository.class);
    private final UserGroupMemberRepository members = mock(UserGroupMemberRepository.class);
    private final UserGroupRepository groups = mock(UserGroupRepository.class);
    private final GroupFeaturePermissionRepository groupPermissions =
            mock(GroupFeaturePermissionRepository.class);
    private final FeaturePermissionService service = new FeaturePermissionService(
            userPermissions, members, groups, groupPermissions);

    // ---------------------------------------------------------------- 用户级

    @Test
    void allowsIsTrueWhenNothingIsConfiguredAtAll() {
        when(userPermissions.countByUserId(USER)).thenReturn(0L);
        when(members.findByUserId(USER)).thenReturn(Optional.empty());

        // 从没配过权限的老用户不能被挡在外面 —— 与 require 的兼容分支一致。
        assertTrue(service.allows(USER, KEY));
    }

    @Test
    void allowsIsTrueWhenUserWhitelistHasItEnabled() {
        when(userPermissions.countByUserId(USER)).thenReturn(1L);
        when(userPermissions.findByUserIdAndFeatureKey(USER, KEY))
                .thenReturn(Optional.of(UserFeaturePermission.builder()
                        .userId(USER).featureKey(KEY).enabled(true).build()));

        assertTrue(service.allows(USER, KEY));
    }

    @Test
    void allowsIsFalseWhenUserWhitelistLacksTheFeature() {
        when(userPermissions.countByUserId(USER)).thenReturn(1L);
        when(userPermissions.findByUserIdAndFeatureKey(USER, KEY)).thenReturn(Optional.empty());

        assertFalse(service.allows(USER, KEY));

        // 一致性：同一个场景 require 必须抛，否则前端置灰就成了"误伤"。
        assertThrows(ResponseStatusException.class, () -> service.require(USER, KEY));
    }

    @Test
    void allowsIsFalseWhenUserWhitelistExplicitlyDisablesIt() {
        when(userPermissions.countByUserId(USER)).thenReturn(1L);
        when(userPermissions.findByUserIdAndFeatureKey(USER, KEY))
                .thenReturn(Optional.of(UserFeaturePermission.builder()
                        .userId(USER).featureKey(KEY).enabled(false).build()));

        assertFalse(service.allows(USER, KEY));
    }

    // ---------------------------------------------------------------- 组级回落

    @Test
    void allowsFallsBackToTheGroupWhitelistWhenUserHasNoRows() {
        givenUserInEnabledGroup();
        when(groupPermissions.countByGroupId(GROUP)).thenReturn(1L);
        when(groupPermissions.findByGroupIdAndFeatureKey(GROUP, KEY))
                .thenReturn(Optional.of(GroupFeaturePermission.builder()
                        .groupId(GROUP).featureKey(KEY).enabled(true).build()));

        assertTrue(service.allows(USER, KEY));
    }

    @Test
    void allowsIsFalseWhenTheGroupWhitelistLacksTheFeature() {
        givenUserInEnabledGroup();
        when(groupPermissions.countByGroupId(GROUP)).thenReturn(1L);
        when(groupPermissions.findByGroupIdAndFeatureKey(GROUP, KEY)).thenReturn(Optional.empty());

        assertFalse(service.allows(USER, KEY));
        assertThrows(ResponseStatusException.class, () -> service.require(USER, KEY));
    }

    /**
     * 用户组被停用后，它的白名单不该继续生效 —— 否则"停用组"只停了个寂寞。
     * 此时按「没有组」处理，走兼容放行。
     */
    @Test
    void allowsIgnoresTheWhitelistOfADisabledGroup() {
        when(userPermissions.countByUserId(USER)).thenReturn(0L);
        when(members.findByUserId(USER)).thenReturn(Optional.of(UserGroupMember.builder()
                .groupId(GROUP).userId(USER).createdAt(LocalDateTime.now()).build()));
        when(groups.findById(GROUP)).thenReturn(Optional.of(UserGroup.builder()
                .id(GROUP).name("research").enabled(false).build()));

        assertTrue(service.allows(USER, KEY));
    }

    private void givenUserInEnabledGroup() {
        when(userPermissions.countByUserId(USER)).thenReturn(0L);
        when(members.findByUserId(USER)).thenReturn(Optional.of(UserGroupMember.builder()
                .groupId(GROUP).userId(USER).createdAt(LocalDateTime.now()).build()));
        when(groups.findById(GROUP)).thenReturn(Optional.of(UserGroup.builder()
                .id(GROUP).name("research").enabled(true).build()));
    }
}
