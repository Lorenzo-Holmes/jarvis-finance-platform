package com.jarvis.research.controller;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.AuthDtos.*;
import com.jarvis.research.security.AuthService;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.security.GitHubOAuthService;
import com.jarvis.research.service.AuthRateLimitService;
import com.jarvis.research.service.EmailVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.io.IOException;

/** 认证 API：JWT 仅写入 HttpOnly Cookie，不暴露给前端 JavaScript。 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Slf4j
public class AuthController {

    private final AuthService authService;
    private final JarvisProperties props;
    private final AuthRateLimitService authRateLimitService;
    private final AuditService auditService;
    private final EmailVerificationService emailVerificationService;
    private final GitHubOAuthService gitHubOAuthService;

    @GetMapping("/csrf")
    public ApiResponse<Map<String, String>> csrf(CsrfToken token) {
        return ApiResponse.ok(Map.of(
                "token", token.getToken(),
                "headerName", token.getHeaderName()
        ));
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest req,
                                              HttpServletRequest request,
                                              HttpServletResponse response) {
        authRateLimitService.checkRegister(clientIp(request));
        String clientIp = clientIp(request);
        AuthResponse auth = authService.register(req, clientIp);
        writeAuthCookie(response, auth.getToken(), auth.getExpiresIn());
        auth.setToken(null);
        return ApiResponse.ok(auth, "注册成功");
    }

    @PostMapping("/verification/email")
    public ApiResponse<Void> sendEmailVerification(@Valid @RequestBody EmailVerificationRequest req,
                                                    HttpServletRequest request) {
        authRateLimitService.checkEmailCodeSend(clientIp(request), req.getEmail());
        emailVerificationService.sendRegistrationCode(req.getEmail());
        return ApiResponse.ok(null, "如果邮箱可用，验证码将发送至该邮箱");
    }

    @PostMapping("/verification/email/confirm")
    public ApiResponse<Void> confirmEmailVerification(
            @Valid @RequestBody EmailVerificationConfirmRequest req) {
        emailVerificationService.confirmRegistrationCode(req.getEmail(), req.getCode());
        return ApiResponse.ok(null, "邮箱验证成功");
    }

    @GetMapping("/github/authorize")
    public void githubAuthorize(HttpServletResponse response) throws IOException {
        response.sendRedirect(gitHubOAuthService.authorizationUrl());
    }

    @GetMapping("/github/callback")
    public void githubCallback(@RequestParam(required = false) String code,
                               @RequestParam(required = false) String state,
                               HttpServletRequest request,
                               HttpServletResponse response) throws IOException {
        try {
            AuthResponse auth = gitHubOAuthService.complete(code, state, clientIp(request));
            writeAuthCookie(response, auth.getToken(), auth.getExpiresIn());
            redirectFrontend(response, "oauth=success");
        } catch (Exception e) {
            // 回调页只显示通用失败状态，详细错误留在服务端日志，避免泄露 OAuth 信息。
            log.warn("GitHub OAuth callback failed", e);
            redirectFrontend(response, "oauth=error");
        }
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest req,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        authRateLimitService.checkLogin(clientIp(request), req.getEmail());
        String clientIp = clientIp(request);
        AuthResponse auth = authService.login(req, clientIp);
        writeAuthCookie(response, auth.getToken(), auth.getExpiresIn());
        auth.setToken(null);
        return ApiResponse.ok(auth, "登录成功");
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me() {
        return ApiResponse.ok(authService.getUserInfo(CurrentUser.id()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        if (CurrentUser.isAuthenticated()) {
            auditService.record(CurrentUser.id(), "USER_LOGOUT", "auth", clientIp(request), "退出登录");
        }
        ResponseCookie.ResponseCookieBuilder cookie = baseCookie("").maxAge(Duration.ZERO);
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.build().toString());
        return ApiResponse.ok(null, "已退出登录");
    }

    private void writeAuthCookie(HttpServletResponse response, String token, long expirationMs) {
        ResponseCookie cookie = baseCookie(token)
                .maxAge(Duration.ofMillis(expirationMs))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String clientIp(HttpServletRequest request) {
        // 生产 Java 只监听 127.0.0.1，外部请求必须经过受信任的 Nginx/Cloudflare。
        String cf = request.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",", 2)[0].trim();
        }
        return request.getRemoteAddr();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        JarvisProperties.Auth auth = props.getAuth();
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie
                .from(auth.getCookieName(), value)
                .httpOnly(true)
                .secure(auth.isCookieSecure())
                .sameSite(auth.getSameSite())
                .path("/");
        if (auth.getCookieDomain() != null && !auth.getCookieDomain().isBlank()) {
            builder.domain(auth.getCookieDomain());
        }
        return builder;
    }

    private void redirectFrontend(HttpServletResponse response, String query) throws IOException {
        String target = props.getOauth().getFrontendRedirectUri();
        if (target == null || target.isBlank()) target = "http://localhost:5173";
        response.sendRedirect(target + (target.contains("?") ? "&" : "?") + query);
    }
}
