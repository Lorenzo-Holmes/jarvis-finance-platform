package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GroupFeaturePermissionRepository extends JpaRepository<GroupFeaturePermission, Long> {
    long countByGroupId(Long groupId);
    List<GroupFeaturePermission> findByGroupIdOrderByFeatureKey(Long groupId);
    Optional<GroupFeaturePermission> findByGroupIdAndFeatureKey(Long groupId, String featureKey);
    void deleteByGroupId(Long groupId);
}
