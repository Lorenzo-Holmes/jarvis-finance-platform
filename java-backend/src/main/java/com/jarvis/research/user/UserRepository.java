package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Page<User> findByEmailContainingIgnoreCaseOrDisplayNameContainingIgnoreCase(
            String email, String displayName, Pageable pageable);
}
