package com.jarvis.research.service;

import com.jarvis.research.user.UserFeaturePermissionRepository;
import com.jarvis.research.user.GroupFeaturePermissionRepository;
import com.jarvis.research.user.UserGroup;
import com.jarvis.research.user.UserGroupMemberRepository;
import com.jarvis.research.user.UserGroupRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 用户功能权限校验。尚未配置任何权限的历史用户保持兼容，配置过后按白名单执行。 */
@Service
@RequiredArgsConstructor
public class FeaturePermissionService {

    private final UserFeaturePermissionRepository repository;
    private final UserGroupMemberRepository groupMemberRepository;
    private final UserGroupRepository groupRepository;
    private final GroupFeaturePermissionRepository groupPermissionRepository;

    @Transactional(readOnly = true)
    public void require(Long userId, String featureKey) {
        String reason = denyReason(userId, featureKey);
        if (reason != null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, reason);
        }
    }

    /**
     * 是否具备某功能权限，<strong>不抛异常</strong>。
     *
     * <p>给「动手之前先置灰」这类只读判断用：让用户在点下去之前就看到不可用，
     * 比收到一个 403 更好（与 {@code ScheduledTaskController#types()} 同一取舍）。</p>
     */
    @Transactional(readOnly = true)
    public boolean allows(Long userId, String featureKey) {
        return denyReason(userId, featureKey) == null;
    }

    /**
     * 权限判定，返回 {@code null} 表示放行，否则返回拒绝原因。
     *
     * <p>顺序：用户级配置优先（配过就按用户白名单），未配置则回落到用户组白名单，
     * 两处都没配置过则放行 —— 保持对历史用户的兼容。</p>
     */
    private String denyReason(Long userId, String featureKey) {
        if (repository.countByUserId(userId) > 0) {
            boolean enabled = repository.findByUserIdAndFeatureKey(userId, featureKey)
                    .map(permission -> permission.isEnabled())
                    .orElse(false);
            return enabled ? null : "当前账号未开通功能：" + featureKey;
        }

        Long groupId = groupMemberRepository.findByUserId(userId)
                .flatMap(member -> groupRepository.findById(member.getGroupId()))
                .filter(UserGroup::isEnabled)
                .map(UserGroup::getId)
                .orElse(null);
        if (groupId == null || groupPermissionRepository.countByGroupId(groupId) == 0) return null;
        boolean enabled = groupPermissionRepository.findByGroupIdAndFeatureKey(groupId, featureKey)
                .map(permission -> permission.isEnabled())
                .orElse(false);
        return enabled ? null : "当前账号所属用户组未开通功能：" + featureKey;
    }
}
