package com.jarvis.research.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CommunityPostRepository extends JpaRepository<CommunityPost, Long> {
    Page<CommunityPost> findByGroupIdOrderByCreatedAtDesc(Long groupId, Pageable pageable);
    long countByGroupId(Long groupId);
    long countByAuthorUserId(Long authorUserId);

    @Query("select p from CommunityPost p where p.groupId is null " +
            "or p.groupId in (select g.id from CommunityGroup g where g.visibility = 'OPEN') " +
            "order by p.createdAt desc")
    Page<CommunityPost> findPublicFeed(Pageable pageable);
}
