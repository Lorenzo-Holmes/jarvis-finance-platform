package com.jarvis.research.user;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** 邮箱一次性验证码。code_hash 不保存明文验证码。 */
@Entity
@Table(name = "email_verification_code", indexes = {
        @Index(name = "idx_email_verification_lookup", columnList = "email,purpose,created_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmailVerificationCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 120)
    private String email;

    @Column(nullable = false, length = 24)
    private String purpose;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
