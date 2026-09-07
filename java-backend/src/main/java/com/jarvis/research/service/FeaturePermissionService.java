package com.jarvis.research.service;

import com.jarvis.research.user.UserFeaturePermissionRepository;
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

    @Transactional(readOnly = true)
    public void require(Long userId, String featureKey) {
        if (repository.countByUserId(userId) == 0) return;
        boolean enabled = repository.findByUserIdAndFeatureKey(userId, featureKey)
                .map(permission -> permission.isEnabled())
                .orElse(false);
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前账号未开通功能：" + featureKey);
        }
    }
}
