package com.jarvis.research.security;

import com.jarvis.research.common.ExternalWebClients;
import com.jarvis.research.config.JarvisProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

import static com.jarvis.research.security.AuthDtos.AuthResponse;

/** GitHub OAuth Authorization Code 流程。access token 仅在内存请求链路中使用，不落库。 */
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private static final String PROVIDER = "GITHUB";

    private final JarvisProperties properties;
    private final OAuthStateStore stateStore;
    private final AuthService authService;

    public String authorizationUrl() {
        JarvisProperties.OAuth oauth = requireEnabled();
        return authorizationUrl(oauth, stateStore.create());
    }

    /** 只有已登录用户才能发起绑定流程。 */
    public String bindingAuthorizationUrl(Long userId) {
        JarvisProperties.OAuth oauth = requireEnabled();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录，无法绑定 GitHub");
        }
        return authorizationUrl(oauth, stateStore.create(userId));
    }

    private String authorizationUrl(JarvisProperties.OAuth oauth, OAuthStateStore.Authorization authorization) {
        return UriComponentsBuilder.fromUriString(oauth.getAuthorizeUrl())
                .queryParam("client_id", oauth.getClientId())
                .queryParam("redirect_uri", oauth.getRedirectUri())
                .queryParam("scope", "read:user user:email")
                .queryParam("state", authorization.state())
                .queryParam("code_challenge", codeChallenge(authorization.codeVerifier()))
                .queryParam("code_challenge_method", "S256")
                .build()
                .encode()
                .toUriString();
    }

    public AuthResponse complete(String code, String state, String clientIp) {
        JarvisProperties.OAuth oauth = requireEnabled();
        OAuthStateStore.Entry stateEntry = stateStore.consume(state);
        if (stateEntry == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 登录状态已失效，请重试");
        }
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "GitHub 授权码缺失");
        }

        WebClient client = ExternalWebClients.create(Duration.ofSeconds(15));
        Map<String, Object> token = client.post()
                .uri(oauth.getTokenUrl())
                .header("Accept", "application/json")
                .bodyValue(Map.of(
                        "client_id", oauth.getClientId(),
                        "client_secret", oauth.getClientSecret(),
                        "code", code,
                        "redirect_uri", oauth.getRedirectUri(),
                        "code_verifier", stateEntry.codeVerifier()))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        String accessToken = token == null ? null : String.valueOf(token.get("access_token"));
        if (accessToken == null || accessToken.isBlank() || "null".equals(accessToken)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub 授权失败");
        }

        Map<String, Object> profile = client.get()
                .uri(oauth.getUserUrl())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "jarvis-finance-platform")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() {})
                .block();
        if (profile == null || profile.get("id") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "无法读取 GitHub 用户信息");
        }
        String providerUserId = String.valueOf(profile.get("id"));
        String login = text(profile.get("login"));
        String displayName = firstNonBlank(text(profile.get("name")), login, "GitHub 用户");
        String email = verifiedEmail(client, oauth, accessToken, profile);
        if (stateEntry.userId() != null) {
            return authService.bindOAuth(stateEntry.userId(), PROVIDER, providerUserId,
                    login, email, displayName, clientIp);
        }
        return authService.loginWithOAuth(PROVIDER, providerUserId, login, email, displayName, clientIp);
    }

    private String codeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("无法生成 OAuth PKCE 挑战值", e);
        }
    }

    private String verifiedEmail(WebClient client, JarvisProperties.OAuth oauth,
                                 String accessToken, Map<String, Object> profile) {
        String profileEmail = text(profile.get("email"));
        if (profileEmail != null && !profileEmail.isBlank()) return profileEmail.trim().toLowerCase();
        List<Map<String, Object>> emails = client.get()
                .uri(oauth.getEmailsUrl())
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "jarvis-finance-platform")
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<Map<String, Object>>>() {})
                .block();
        if (emails != null) {
            for (Map<String, Object> item : emails) {
                if (Boolean.TRUE.equals(item.get("verified")) && item.get("email") != null) {
                    return String.valueOf(item.get("email")).trim().toLowerCase();
                }
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "GitHub 账号没有可验证邮箱，请先在 GitHub 设置公开或验证邮箱");
    }

    private JarvisProperties.OAuth requireEnabled() {
        JarvisProperties.OAuth oauth = properties.getOauth();
        if (!oauth.isEnabled() || oauth.getClientId() == null || oauth.getClientId().isBlank()
                || oauth.getClientSecret() == null || oauth.getClientSecret().isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "GitHub 登录尚未配置");
        }
        return oauth;
    }

    private String text(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "GitHub 用户";
    }
}
