package com.jarvis.research.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {
    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
            String email, String purpose);

    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndVerifiedAtIsNotNullAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, String purpose, LocalDateTime now);
}
