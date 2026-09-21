package com.jarvis.research.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommunityGroupRepository extends JpaRepository<CommunityGroup, Long> {
    Page<CommunityGroup> findAllByOrderByUpdatedAtDesc(Pageable pageable);
}
