package com.jarvis.research.security;

import com.jarvis.research.config.JarvisProperties;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 单次 GitHub OAuth state 存储。多实例部署时应替换为 Redis。 */
@Component
public class OAuthStateStore {

    private final JarvisProperties properties;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Entry> states = new ConcurrentHashMap<>();

    public OAuthStateStore(JarvisProperties properties) {
        this.properties = properties;
    }

    public Authorization create() {
        return create(null);
    }

    /**
     * 创建一次性 OAuth 状态。绑定流程会把当前用户 ID 固定在服务端状态中，
     * 回调时不会信任浏览器传入的用户标识。
     */
    public Authorization create(Long userId) {
        cleanup();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        byte[] verifierBytes = new byte[32];
        random.nextBytes(verifierBytes);
        String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
        states.put(state, new Entry(Instant.now(), userId, verifier));
        return new Authorization(state, verifier);
    }

    public Entry consume(String state) {
        if (state == null || state.isBlank()) return null;
        Entry entry = states.remove(state);
        return entry != null && Instant.now().isBefore(entry.createdAt()
                .plusSeconds(Math.max(60, properties.getOauth().getStateTtlSeconds())))
                ? entry : null;
    }

    private void cleanup() {
        Instant threshold = Instant.now().minusSeconds(Math.max(60, properties.getOauth().getStateTtlSeconds()));
        states.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(threshold));
    }

    public record Authorization(String state, String codeVerifier) {}

    public record Entry(Instant createdAt, Long userId, String codeVerifier) {}
}
