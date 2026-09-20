package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserGroupMemberRepository extends JpaRepository<UserGroupMember, Long> {
    List<UserGroupMember> findByGroupIdOrderByCreatedAtAsc(Long groupId);
    Optional<UserGroupMember> findByUserId(Long userId);
    void deleteByGroupId(Long groupId);
    void deleteByUserId(Long userId);
    long countByGroupId(Long groupId);
}
