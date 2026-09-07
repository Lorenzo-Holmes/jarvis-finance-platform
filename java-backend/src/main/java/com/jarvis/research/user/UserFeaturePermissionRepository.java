package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserFeaturePermissionRepository extends JpaRepository<UserFeaturePermission, Long> {
    long countByUserId(Long userId);
    List<UserFeaturePermission> findByUserIdOrderByFeatureKey(Long userId);
    Optional<UserFeaturePermission> findByUserIdAndFeatureKey(Long userId, String featureKey);
    void deleteByUserId(Long userId);
}
