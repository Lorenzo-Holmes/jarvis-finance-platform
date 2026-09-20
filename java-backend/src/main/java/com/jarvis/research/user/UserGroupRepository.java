package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UserGroupRepository extends JpaRepository<UserGroup, Long> {
    List<UserGroup> findAllByOrderByCreatedAtDesc();
    Optional<UserGroup> findByNameIgnoreCase(String name);
}
