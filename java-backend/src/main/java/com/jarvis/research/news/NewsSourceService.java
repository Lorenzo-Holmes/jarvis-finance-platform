package com.jarvis.research.news;

import com.jarvis.research.service.AiProxyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.jarvis.research.news.NewsDtos.SourceRequest;
import static com.jarvis.research.news.NewsDtos.SubscriptionRequest;

/**
 * RSS 配置与用户订阅服务。
 *
 * <p>Java 是配置、权限与持久化的唯一事实来源；Python 只接收同步后的抓取配置，
 * 不允许浏览器直接修改 Python 内存状态。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NewsSourceService {

    private final NewsSourceRepository sourceRepository;
    private final NewsSubscriptionRepository subscriptionRepository;
    private final AiProxyService aiProxyService;

    @EventListener(ApplicationReadyEvent.class)
    public void syncSourcesWhenReady() {
        sourceRepository.findAll().forEach(this::syncToPython);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSources(boolean enabledOnly) {
        List<NewsSource> sources = enabledOnly
                ? sourceRepository.findAllByEnabledTrueOrderByNameAsc()
                : sourceRepository.findAllByOrderByNameAsc();
        return sources.stream().map(this::sourceMap).toList();
    }

    @Transactional
    public Map<String, Object> createSource(SourceRequest request) {
        String key = clean(request.getSourceKey());
        if (sourceRepository.findBySourceKey(key).isPresent()) {
            throw new IllegalArgumentException("RSS sourceKey 已存在: " + key);
        }
        if (sourceRepository.findByUrl(clean(request.getUrl())).isPresent()) {
            throw new IllegalArgumentException("RSS URL 已被其他来源使用");
        }
        NewsSource source = new NewsSource();
        apply(source, request, key);
        NewsSource saved = sourceRepository.save(source);
        syncToPython(saved);
        return sourceMap(saved);
    }

    @Transactional
    public Map<String, Object> updateSource(String sourceKey, SourceRequest request) {
        String key = clean(sourceKey);
        NewsSource source = sourceRepository.findBySourceKey(key)
                .orElseThrow(() -> new IllegalArgumentException("RSS source 不存在: " + key));
        String url = clean(request.getUrl());
        sourceRepository.findByUrl(url)
                .filter(other -> !key.equals(other.getSourceKey()))
                .ifPresent(other -> {
                    throw new IllegalArgumentException("RSS URL 已被其他来源使用");
                });
        apply(source, request, key);
        NewsSource saved = sourceRepository.save(source);
        syncToPython(saved);
        return sourceMap(saved);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> subscriptions(Long userId) {
        List<NewsSubscription> rows = subscriptionRepository.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(userId);
        List<String> sourceKeys = rows.stream()
                .map(NewsSubscription::getSourceKey)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        List<String> topics = rows.stream()
                .map(NewsSubscription::getTopic)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
        return Map.of("sourceKeys", sourceKeys, "topics", topics);
    }

    @Transactional
    public Map<String, Object> replaceSubscriptions(Long userId, SubscriptionRequest request) {
        Set<String> sourceKeys = cleanDistinct(request.getSourceKeys());
        Set<String> topics = cleanDistinct(request.getTopics());
        Set<String> enabledSourceKeys = sourceRepository.findAllByEnabledTrueOrderByNameAsc().stream()
                .map(NewsSource::getSourceKey)
                .collect(Collectors.toSet());
        List<String> unknown = sourceKeys.stream()
                .filter(key -> !enabledSourceKeys.contains(key))
                .toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("不可订阅已停用或不存在的 RSS 来源: " + String.join(", ", unknown));
        }

        subscriptionRepository.deleteByUserId(userId);
        LocalDateTimeHolder now = new LocalDateTimeHolder();
        List<NewsSubscription> rows = new ArrayList<>();
        sourceKeys.forEach(key -> rows.add(subscription(userId, key, "", now.value())));
        topics.forEach(topic -> rows.add(subscription(userId, "", topic, now.value())));
        subscriptionRepository.saveAll(rows);
        return subscriptions(userId);
    }

    /** 按当前用户的订阅在 Java 侧过滤，避免用户通过修改前端隐藏筛选越权看源配置。 */
    @Transactional(readOnly = true)
    public Map<String, Object> filterDigest(Long userId, Map<String, Object> shaped) {
        if (userId == null || shaped == null) return shaped;
        List<NewsSubscription> rows = subscriptionRepository.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(userId);
        if (rows.isEmpty()) return shaped;

        Set<String> sourceKeys = rows.stream().map(NewsSubscription::getSourceKey)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        Set<String> topics = rows.stream().map(NewsSubscription::getTopic)
                .filter(value -> value != null && !value.isBlank()).collect(Collectors.toSet());
        List<Map<String, Object>> items = new ArrayList<>();
        Object rawItems = shaped.get("items");
        if (rawItems instanceof List<?> list) {
            for (Object value : list) {
                if (!(value instanceof Map<?, ?> raw)) continue;
                String sourceKey = text(raw.get("source_id"));
                String category = text(raw.get("category"));
                if (sourceKeys.contains(sourceKey) || topics.contains(category)) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    raw.forEach((key, itemValue) -> item.put(String.valueOf(key), itemValue));
                    items.add(item);
                }
            }
        }
        Map<String, Object> filtered = new LinkedHashMap<>(shaped);
        filtered.put("items", items);
        return filtered;
    }

    private void apply(NewsSource source, SourceRequest request, String sourceKey) {
        source.setSourceKey(sourceKey);
        source.setName(clean(request.getName()));
        source.setUrl(clean(request.getUrl()));
        source.setCategory(clean(request.getCategory()).isBlank() ? "general" : clean(request.getCategory()));
        source.setCredibility(request.getCredibility() == null ? 50 : request.getCredibility());
        source.setEnabled(request.getEnabled() == null || request.getEnabled());
    }

    private NewsSubscription subscription(Long userId, String sourceKey, String topic, java.time.LocalDateTime now) {
        return NewsSubscription.builder()
                .userId(userId)
                .sourceKey(sourceKey)
                .topic(topic)
                .enabled(true)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    private void syncToPython(NewsSource source) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("id", source.getSourceKey());
            payload.put("name", source.getName());
            payload.put("url", source.getUrl());
            payload.put("category", source.getCategory());
            payload.put("credibility", source.getCredibility());
            payload.put("enabled", source.isEnabled());
            aiProxyService.post("/internal/rss/source", payload);
        } catch (Exception error) {
            // 配置已经落库；Python 短暂不可用时由启动同步/下一次更新重试，不能回滚管理员操作。
            log.warn("同步 RSS source 到 Python 失败，sourceKey={}: {}", source.getSourceKey(), error.getMessage());
        }
    }

    private Map<String, Object> sourceMap(NewsSource source) {
        return Map.of(
                "sourceKey", source.getSourceKey(),
                "name", source.getName(),
                "url", source.getUrl(),
                "category", source.getCategory(),
                "credibility", source.getCredibility(),
                "enabled", source.isEnabled());
    }

    private Set<String> cleanDistinct(List<String> values) {
        if (values == null) return Collections.emptySet();
        return values.stream().map(this::clean).filter(value -> !value.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 避免在集合构造时为每一行生成略有差异的时间戳。 */
    private static final class LocalDateTimeHolder {
        private final java.time.LocalDateTime value = java.time.LocalDateTime.now();
        private java.time.LocalDateTime value() { return value; }
    }
}
