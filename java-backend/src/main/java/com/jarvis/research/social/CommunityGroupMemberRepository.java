package com.jarvis.research.social;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CommunityGroupMemberRepository extends JpaRepository<CommunityGroupMember, Long> {
    boolean existsByGroupIdAndUserId(Long groupId, Long userId);
    Optional<CommunityGroupMember> findByGroupIdAndUserId(Long groupId, Long userId);
    List<CommunityGroupMember> findByGroupIdOrderByCreatedAtAsc(Long groupId);
    long countByGroupId(Long groupId);
    long countByUserId(Long userId);
    void deleteByGroupIdAndUserId(Long groupId, Long userId);
    List<CommunityGroupMember> findByGroupIdInAndUserId(Collection<Long> groupIds, Long userId);

    @Query("select m.groupId, count(m) from CommunityGroupMember m where m.groupId in :groupIds group by m.groupId")
    List<Object[]> countByGroupIds(@Param("groupIds") Collection<Long> groupIds);
}
