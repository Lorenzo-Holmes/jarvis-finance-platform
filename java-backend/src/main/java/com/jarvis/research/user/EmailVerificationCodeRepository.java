package com.jarvis.research.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.time.LocalDateTime;
import java.util.Optional;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
            String email, String purpose);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<EmailVerificationCode> findTopByEmailAndPurposeAndVerifiedAtIsNotNullAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, String purpose, LocalDateTime now);
}
