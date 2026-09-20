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
        if (repository.countByUserId(userId) > 0) {
            boolean enabled = repository.findByUserIdAndFeatureKey(userId, featureKey)
                    .map(permission -> permission.isEnabled())
                    .orElse(false);
            if (!enabled) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号未开通功能：" + featureKey);
            }
            return;
        }

        Long groupId = groupMemberRepository.findByUserId(userId)
                .flatMap(member -> groupRepository.findById(member.getGroupId()))
                .filter(UserGroup::isEnabled)
                .map(UserGroup::getId)
                .orElse(null);
        if (groupId == null || groupPermissionRepository.countByGroupId(groupId) == 0) return;
        boolean enabled = groupPermissionRepository.findByGroupIdAndFeatureKey(groupId, featureKey)
                .map(permission -> permission.isEnabled())
                .orElse(false);
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号所属用户组未开通功能：" + featureKey);
        }
    }
}
