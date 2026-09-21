package com.jarvis.research.social;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CommunityGroupRepository extends JpaRepository<CommunityGroup, Long> {
    Page<CommunityGroup> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    Page<CommunityGroup> findByNameContainingIgnoreCaseOrderByUpdatedAtDesc(String name, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from CommunityGroup g where g.id = :id")
    Optional<CommunityGroup> findByIdForMembershipUpdate(@Param("id") Long id);

    @Query("select g from CommunityGroup g where g.visibility = 'OPEN' " +
            "or g.id in (select m.groupId from CommunityGroupMember m where m.userId = :userId) " +
            "order by g.updatedAt desc, g.id desc")
    Page<CommunityGroup> findVisibleToUser(@Param("userId") Long userId, Pageable pageable);

    @Query("select g from CommunityGroup g where (g.visibility = 'OPEN' " +
            "or g.id in (select m.groupId from CommunityGroupMember m where m.userId = :userId)) " +
            "and lower(g.name) like lower(concat('%', :name, '%')) order by g.updatedAt desc, g.id desc")
    Page<CommunityGroup> findVisibleToUserByName(@Param("userId") Long userId,
                                                 @Param("name") String name,
                                                 Pageable pageable);
}
