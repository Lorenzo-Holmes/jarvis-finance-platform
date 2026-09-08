package com.jarvis.research.service;

import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.user.EmailVerificationCode;
import com.jarvis.research.user.EmailVerificationCodeRepository;
import com.jarvis.research.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/** Resend 邮箱验证码：明文只在发送瞬间存在，数据库只保存 BCrypt hash。 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String REGISTER_PURPOSE = "REGISTER";
    private static final String PASSWORD_RESET_PURPOSE = "PASSWORD_RESET";

    private final EmailVerificationCodeRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JarvisProperties properties;
    private final UserRepository userRepository;

    @Transactional
    public void sendRegistrationCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        LocalDateTime now = LocalDateTime.now();
        EmailVerificationCode latest = repository
                .findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, REGISTER_PURPOSE)
                .orElse(null);
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().plusSeconds(60).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }

        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1_000_000));
        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email(email)
                .purpose(REGISTER_PURPOSE)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusSeconds(properties.getEmail().getCodeTtlSeconds()))
                .attempts(0)
                .createdAt(now)
                .build();
        repository.save(entity);
        sendByResend(email, code,
                "JARVIS 金融投研平台注册验证码",
                "你的注册验证码是：");
    }

    /**
     * 请求密码重置验证码。不存在的邮箱直接静默返回，Controller 始终给出通用提示，
     * 避免利用接口枚举平台注册邮箱。
     */
    @Transactional
    public void sendPasswordResetCode(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        if (!userRepository.existsByEmail(email)) return;

        LocalDateTime now = LocalDateTime.now();
        EmailVerificationCode latest = repository
                .findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, PASSWORD_RESET_PURPOSE)
                .orElse(null);
        if (latest != null && latest.getCreatedAt() != null
                && latest.getCreatedAt().plusSeconds(60).isAfter(now)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "验证码发送过于频繁，请稍后再试");
        }

        String code = String.valueOf(ThreadLocalRandom.current().nextInt(100000, 1_000_000));
        EmailVerificationCode entity = EmailVerificationCode.builder()
                .email(email)
                .purpose(PASSWORD_RESET_PURPOSE)
                .codeHash(passwordEncoder.encode(code))
                .expiresAt(now.plusSeconds(properties.getEmail().getCodeTtlSeconds()))
                .attempts(0)
                .createdAt(now)
                .build();
        repository.save(entity);
        sendByResend(email, code,
                "JARVIS 金融投研平台密码重置验证码",
                "你的密码重置验证码是：");
    }

    @Transactional
    public void confirmRegistrationCode(String rawEmail, String rawCode) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        EmailVerificationCode entity = repository
                .findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, REGISTER_PURPOSE)
                .orElseThrow(() -> invalidCode());
        LocalDateTime now = LocalDateTime.now();
        if (entity.getExpiresAt().isBefore(now)
                || entity.getAttempts() >= properties.getEmail().getMaxVerifyAttempts()) {
            throw invalidCode();
        }
        // 已验证凭证允许幂等确认，避免注册请求因密码校验等原因失败后无法重试。
        if (entity.getVerifiedAt() != null) return;
        entity.setAttempts(entity.getAttempts() + 1);
        if (!passwordEncoder.matches(code, entity.getCodeHash())) {
            repository.save(entity);
            throw invalidCode();
        }
        entity.setVerifiedAt(now);
        repository.save(entity);
    }

    /** 密码重置时原子校验并消费一次性验证码。 */
    @Transactional
    public void consumePasswordResetVerification(String rawEmail, String rawCode) {
        String email = normalizeEmail(rawEmail);
        String code = rawCode == null ? "" : rawCode.trim();
        EmailVerificationCode entity = repository
                .findTopByEmailAndPurposeAndUsedAtIsNullOrderByCreatedAtDesc(email, PASSWORD_RESET_PURPOSE)
                .orElseThrow(() -> invalidCode());
        LocalDateTime now = LocalDateTime.now();
        if (entity.getExpiresAt().isBefore(now)
                || entity.getAttempts() >= properties.getEmail().getMaxVerifyAttempts()) {
            throw invalidCode();
        }
        entity.setAttempts(entity.getAttempts() + 1);
        if (!passwordEncoder.matches(code, entity.getCodeHash())) {
            repository.save(entity);
            throw invalidCode();
        }
        entity.setVerifiedAt(now);
        entity.setUsedAt(now);
        repository.save(entity);
    }

    /** 注册事务中消费已验证凭证，防止同一个验证码重复注册。 */
    @Transactional
    public void consumeRegistrationVerification(String rawEmail) {
        String email = normalizeEmail(rawEmail);
        EmailVerificationCode entity = repository
                .findTopByEmailAndPurposeAndVerifiedAtIsNotNullAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
                        email, REGISTER_PURPOSE, LocalDateTime.now())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请先完成邮箱验证码验证"));
        entity.setUsedAt(LocalDateTime.now());
        repository.save(entity);
    }

    private void sendByResend(String email, String code, String subject, String intro) {
        JarvisProperties.Email config = properties.getEmail();
        if (!"resend".equalsIgnoreCase(config.getProvider())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "当前邮箱服务商未实现");
        }
        if (config.getApiKey() == null || config.getApiKey().isBlank()
                || config.getFrom() == null || config.getFrom().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "邮箱验证码服务未配置");
        }
        WebClient client = ExternalWebClients.create(Duration.ofSeconds(12));
        try {
            client.post()
                    .uri(config.getApiUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + config.getApiKey())
                    .bodyValue(Map.of(
                            "from", config.getFrom(),
                            "to", List.of(email),
                            "subject", subject,
                            "html", "<p>" + intro + "</p><p style=\"font-size:28px;font-weight:bold;letter-spacing:6px\">"
                                    + code + "</p><p>验证码 10 分钟内有效。如非本人操作，请忽略此邮件。</p>"))
                    .retrieve()
                    .toBodilessEntity()
                    .block();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "验证码邮件发送失败，请稍后重试");
        }
    }

    private String normalizeEmail(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "邮箱不能为空");
        }
        return value.trim().toLowerCase();
    }

    private ResponseStatusException invalidCode() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码无效、已过期或错误次数过多");
    }
}
