package com.jarvis.research.service;

import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.user.EmailVerificationCode;
import com.jarvis.research.user.EmailVerificationCodeRepository;
import com.jarvis.research.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmailVerificationServiceTest {

    @Test
    void passwordResetCodeIsConsumedOnceAndMarkedUsed() {
        EmailVerificationCodeRepository repository = mock(EmailVerificationCodeRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserRepository userRepository = mock(UserRepository.class);
        JarvisProperties properties = new JarvisProperties();
        EmailVerificationService service = new EmailVerificationService(
                repository, passwordEncoder, properties, userRepository);

        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email("user@example.com")
                .purpose("PASSWORD_RESET")
                .codeHash("hash")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(repository.findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", "PASSWORD_RESET")).thenReturn(Optional.of(entity));
        when(passwordEncoder.matches("123456", "hash")).thenReturn(true);

        service.consumePasswordResetVerification(" USER@example.com ", "123456");

        assertEquals(1, entity.getAttempts());
        assertNotNull(entity.getVerifiedAt());
        assertNotNull(entity.getUsedAt());
        verify(repository).save(entity);
    }

    @Test
    void wrongPasswordResetCodeConsumesAnAttemptButNotTheCredential() {
        EmailVerificationCodeRepository repository = mock(EmailVerificationCodeRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserRepository userRepository = mock(UserRepository.class);
        JarvisProperties properties = new JarvisProperties();
        EmailVerificationService service = new EmailVerificationService(
                repository, passwordEncoder, properties, userRepository);

        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email("user@example.com")
                .purpose("PASSWORD_RESET")
                .codeHash("hash")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .attempts(0)
                .createdAt(LocalDateTime.now().minusMinutes(1))
                .build();
        when(repository.findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(
                "user@example.com", "PASSWORD_RESET")).thenReturn(Optional.of(entity));
        when(passwordEncoder.matches("000000", "hash")).thenReturn(false);

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service.consumePasswordResetVerification("user@example.com", "000000"));

        assertEquals(400, error.getStatusCode().value());
        assertEquals(1, entity.getAttempts());
        assertNull(entity.getVerifiedAt());
        assertNull(entity.getUsedAt());
        verify(repository).save(entity);
    }

    @Test
    void passwordResetRequestDoesNotRevealOrPersistUnknownEmail() {
        EmailVerificationCodeRepository repository = mock(EmailVerificationCodeRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        UserRepository userRepository = mock(UserRepository.class);
        JarvisProperties properties = new JarvisProperties();
        EmailVerificationService service = new EmailVerificationService(
                repository, passwordEncoder, properties, userRepository);
        when(userRepository.existsByEmail("missing@example.com")).thenReturn(false);

        assertDoesNotThrow(() -> service.sendPasswordResetCode(" Missing@example.com "));

        verify(userRepository).existsByEmail("missing@example.com");
        verifyNoInteractions(repository, passwordEncoder);
    }
}
