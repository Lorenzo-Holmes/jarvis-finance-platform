package com.jarvis.research.security;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.user.*;
import com.jarvis.research.security.AuthDtos.*;
import com.jarvis.research.service.EmailVerificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 认证服务: 注册 / 登录 / 创建模拟账户
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final SimAccountRepository accountRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuditService auditService;
    private final OAuthAccountRepository oauthAccountRepository;
    private final EmailVerificationService emailVerificationService;
    private final JarvisProperties properties;

    /** 注册: 创建用户 + 默认模拟盘账户(10万) */
    @Transactional
    public AuthResponse register(RegisterRequest req, String clientIp) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该邮箱已注册");
        }
        if (propertiesRequireEmailVerification()) {
            emailVerificationService.consumeRegistrationVerification(email);
        }
        User user = User.builder()
                .email(email)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .displayName(req.getDisplayName() == null || req.getDisplayName().isBlank()
                        ? email : req.getDisplayName())
                .enabled(true)
                .createdAt(LocalDateTime.now())
                .build();
        user = userRepository.save(user);

        // 创建默认模拟盘账户
        SimAccount account = SimAccount.builder()
                .userId(user.getId())
                .initialCash(new BigDecimal("100000.0000"))
                .cash(new BigDecimal("100000.0000"))
                .status("ACTIVE")
                .createdAt(LocalDateTime.now())
                .build();
        accountRepository.save(account);
        auditService.record(user.getId(), "USER_REGISTER", "auth", clientIp, "注册成功");

        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtUtil.getExpiration(), toUserInfo(user, account));
    }

    /** OAuth 登录：已绑定账号直接登录；同邮箱且来自 GitHub 的已验证邮箱可自动绑定。 */
    @Transactional
    public AuthResponse loginWithOAuth(String provider, String providerUserId, String providerLogin,
                                       String email, String displayName, String clientIp) {
        OAuthAccount binding = oauthAccountRepository
                .findByProviderAndProviderUserId(provider, providerUserId)
                .orElse(null);
        User user;
        boolean created = false;
        if (binding != null) {
            user = userRepository.findById(binding.getUserId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录账号不存在"));
            binding.setProviderLogin(providerLogin);
            binding.setEmail(email);
            oauthAccountRepository.save(binding);
        } else {
            user = userRepository.findByEmail(email).orElse(null);
            if (user == null) {
                user = User.builder()
                        .email(email)
                        // OAuth-only 账号使用随机不可知密码，后续可在账户设置中设置密码。
                        .passwordHash(passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                        .displayName(displayName)
                        .enabled(true)
                        .createdAt(LocalDateTime.now())
                        .build();
                user = userRepository.save(user);
                created = true;
            }
            oauthAccountRepository.save(OAuthAccount.builder()
                    .userId(user.getId())
                    .provider(provider)
                    .providerUserId(providerUserId)
                    .providerLogin(providerLogin)
                    .email(email)
                    .createdAt(LocalDateTime.now())
                    .updatedAt(LocalDateTime.now())
                    .build());
        }
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        SimAccount account = ensureSimAccount(user.getId());
        auditService.record(user.getId(), created ? "USER_GITHUB_REGISTER" : "USER_GITHUB_LOGIN",
                "auth", clientIp, "GitHub OAuth 登录成功");
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtUtil.getExpiration(), toUserInfo(user, account));
    }

    private boolean propertiesRequireEmailVerification() {
        return properties.getAuth().isRequireEmailVerification();
    }

    /** 登录 */
    @Transactional
    public AuthResponse login(LoginRequest req, String clientIp) {
        String email = req.getEmail().trim().toLowerCase();
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误"));
        if (!user.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "账号已被禁用");
        }
        if (!passwordEncoder.matches(req.getPassword(), user.getPasswordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "邮箱或密码错误");
        }
        SimAccount account = ensureSimAccount(user.getId());
        auditService.record(user.getId(), "USER_LOGIN", "auth", clientIp, "登录成功");
        String token = jwtUtil.generateToken(user.getId(), user.getEmail());
        return new AuthResponse(token, jwtUtil.getExpiration(), toUserInfo(user, account));
    }

    @Transactional(readOnly = true)
    public UserInfo getUserInfo(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "用户不存在"));
        SimAccount account = accountRepository.findByUserId(userId).orElse(null);
        return toUserInfo(user, account);
    }

    private UserInfo toUserInfo(User user, SimAccount account) {
        UserInfo info = new UserInfo();
        info.setId(user.getId());
        info.setEmail(user.getEmail());
        info.setDisplayName(user.getDisplayName());
        info.setRole(user.getRole());
        info.setEnabled(user.isEnabled());
        if (account != null) {
            info.setSimCash(account.getCash());
            info.setSimInitialCash(account.getInitialCash());
        }
        return info;
    }

    private SimAccount ensureSimAccount(Long userId) {
        return accountRepository.findByUserId(userId).orElseGet(() -> {
            SimAccount a = SimAccount.builder()
                    .userId(userId).initialCash(new BigDecimal("100000.0000"))
                    .cash(new BigDecimal("100000.0000")).status("ACTIVE")
                    .createdAt(LocalDateTime.now()).build();
            return accountRepository.save(a);
        });
    }
}
