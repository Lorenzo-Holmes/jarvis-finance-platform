package com.jarvis.research.social;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface CommunityGroupMemberRepository extends JpaRepository<CommunityGroupMember, Long> {
    boolean existsByGroupIdAndUserId(Long groupId, Long userId);
    Optional<CommunityGroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
    List<CommunityGroupMember> findByGroupIdOrderByCreatedAtAsc(Long groupId);
    long countByGroupId(Long groupId);
    long countByUserId(Long userId);
    void deleteByGroupIdAndUserId(Long groupId, Long userId);
}
