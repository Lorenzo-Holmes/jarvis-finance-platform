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

    public String create() {
        cleanup();
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        states.put(state, new Entry(Instant.now()));
        return state;
    }

    public boolean consume(String state) {
        if (state == null || state.isBlank()) return false;
        Entry entry = states.remove(state);
        return entry != null && Instant.now().isBefore(entry.createdAt()
                .plusSeconds(Math.max(60, properties.getOauth().getStateTtlSeconds())));
    }

    private void cleanup() {
        Instant threshold = Instant.now().minusSeconds(Math.max(60, properties.getOauth().getStateTtlSeconds()));
        states.entrySet().removeIf(entry -> entry.getValue().createdAt().isBefore(threshold));
    }

    private record Entry(Instant createdAt) {}
}
