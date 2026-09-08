package com.jarvis.research.security;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.AuthDtos.PasswordResetRequest;
import com.jarvis.research.security.AuthDtos.ProfileUpdateRequest;
import com.jarvis.research.service.EmailVerificationService;
import com.jarvis.research.user.OAuthAccountRepository;
import com.jarvis.research.user.SimAccountRepository;
import com.jarvis.research.user.User;
import com.jarvis.research.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Test
    void passwordResetConsumesOtpRotatesCredentialVersionAndAudits() {
        UserRepository userRepository = mock(UserRepository.class);
        SimAccountRepository accountRepository = mock(SimAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        AuditService auditService = mock(AuditService.class);
        OAuthAccountRepository oauthAccountRepository = mock(OAuthAccountRepository.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        JarvisProperties properties = new JarvisProperties();

        User user = User.builder()
                .id(42L)
                .email("user@example.com")
                .passwordHash("old-hash")
                .displayName("User")
                .credentialVersion(3)
                .build();
        when(userRepository.findByEmail("user@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("new-password-123")).thenReturn("new-hash");

        AuthService service = new AuthService(
                userRepository, accountRepository, passwordEncoder, jwtUtil, auditService,
                oauthAccountRepository, emailVerificationService, properties);

        PasswordResetRequest request = new PasswordResetRequest();
        request.setEmail(" USER@example.com ");
        request.setCode("123456");
        request.setNewPassword("new-password-123");

        service.resetPassword(request, "203.0.113.7");

        verify(emailVerificationService).consumePasswordResetVerification("user@example.com", "123456");
        verify(userRepository).save(user);
        verify(auditService).record(42L, "PASSWORD_RESET", "auth", "203.0.113.7", "邮箱验证码重置密码");
        assertEquals("new-hash", user.getPasswordHash());
        assertEquals(4, user.getCredentialVersion());
    }

    @Test
    void profileUpdatePersistsTrimmedDisplayName() {
        UserRepository userRepository = mock(UserRepository.class);
        SimAccountRepository accountRepository = mock(SimAccountRepository.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtUtil jwtUtil = mock(JwtUtil.class);
        AuditService auditService = mock(AuditService.class);
        OAuthAccountRepository oauthAccountRepository = mock(OAuthAccountRepository.class);
        EmailVerificationService emailVerificationService = mock(EmailVerificationService.class);
        JarvisProperties properties = new JarvisProperties();

        User user = User.builder()
                .id(7L)
                .email("profile@example.com")
                .displayName("Before")
                .build();
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(accountRepository.findByUserId(7L)).thenReturn(Optional.empty());

        AuthService service = new AuthService(
                userRepository, accountRepository, passwordEncoder, jwtUtil, auditService,
                oauthAccountRepository, emailVerificationService, properties);
        ProfileUpdateRequest request = new ProfileUpdateRequest();
        request.setDisplayName("  After  ");

        var result = service.updateProfile(7L, request, "203.0.113.8");

        assertEquals("After", user.getDisplayName());
        assertEquals("After", result.getDisplayName());
        verify(userRepository).save(user);
        verify(auditService).record(7L, "PROFILE_UPDATE", "auth", "203.0.113.8", "更新昵称");
    }
}
