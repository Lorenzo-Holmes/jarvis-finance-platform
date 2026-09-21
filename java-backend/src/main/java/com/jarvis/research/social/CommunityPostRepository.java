package com.jarvis.research.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {
    Page<CommunityPost> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);
    long countByGroupId(Long groupId);
    long countByAuthorUserId(Long authorUserId);

    @Query("select p.groupId, count(p) from CommunityPost p where p.groupId in :groupIds group by p.groupId")
    List<Object[]> countByGroupIds(@Param("groupIds") Collection<Long> groupIds);

    @Query("select p.id from CommunityPost p where p.groupId = :groupId")
    List<Long> findIdsByGroupId(@Param("groupId") Long groupId);

    @Query("select p from CommunityPost p where p.groupId is null " +
            "or p.groupId in (select g.id from CommunityGroup g where g.visibility = 'OPEN') " +
            "or p.groupId in (select m.groupId from CommunityGroupMember m where m.userId = :userId) " +
            "order by p.createdAt desc")
    Page<CommunityPost> findVisibleFeed(@org.springframework.data.repository.query.Param("userId") Long userId,
                                        Pageable pageable);
}
