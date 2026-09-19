package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.security.CurrentUser;
import com.jarvis.research.service.AiProxyService;
import com.jarvis.research.service.AiRateLimitService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 每日要闻。
 *
 * <p>数据链路：Java（本控制器，对外 API 与鉴权）→ Python {@code /internal/rss/digest}
 * （负责抓取、去重、排序）。Java 负责整形、中文标题增强与降级。</p>
 *
 * <p>降级契约：RSS/Python 不可用时返回 {@code available=false}；标题翻译失败则逐条
 * 回退原文，绝不因为 AI 翻译异常丢掉已经抓到的新闻。</p>
 */
@RestController
@RequestMapping("/api/news")
public class NewsController {

    private static final Pattern HAN_TEXT = Pattern.compile("\\p{IsHan}");
    private static final int MAX_TRANSLATION_CACHE = 2_000;

    private final AiProxyService aiProxyService;
    private final AiRateLimitService aiRateLimitService;
    private final Map<String, String> titleZhCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_TRANSLATION_CACHE;
                }
            });

    @Autowired
    public NewsController(AiProxyService aiProxyService, AiRateLimitService aiRateLimitService) {
        this.aiProxyService = aiProxyService;
        this.aiRateLimitService = aiRateLimitService;
    }

    /** 兼容不加载 Spring 容器的轻量单元测试。 */
    public NewsController(AiProxyService aiProxyService) {
        this(aiProxyService, null);
    }

    /**
     * 最新要闻。
     *
     * @param limit   返回条数上限（只截断，不改顺序）
     * @param refresh 是否触发一次抓取（受 Python 侧最小间隔限制）
     * @param force   绕过最小抓取间隔，用户手工点刷新时用
     */
    @GetMapping("/daily")
    public ApiResponse<Object> daily(@RequestParam(defaultValue = "12") int limit,
                                     @RequestParam(defaultValue = "true") boolean refresh,
                                     @RequestParam(defaultValue = "false") boolean force) {
        String path = "/internal/rss/digest?refresh=" + refresh + "&force=" + force;
        try {
            Map<String, Object> digest = aiProxyService.post(path, Map.of());
            Map<String, Object> shaped = NewsDigest.fromDigest(digest, limit);
            return ApiResponse.ok(localizeCachedTitles(shaped));
        } catch (Exception e) {
            return ApiResponse.ok(NewsDigest.unavailable(NewsDigest.REASON_UNAVAILABLE));
        }
    }

    /**
     * 标题翻译是独立增强请求，不阻塞 /daily。前端先拿到 RSS，再异步请求中文标题。
     * 请求与返回严格逐项对应；翻译失败时回退原文。
     */
    @PostMapping("/translate")
    public ApiResponse<Object> translate(@RequestBody Map<String, Object> body) {
        Object rawTitles = body == null ? null : body.get("titles");
        if (!(rawTitles instanceof List<?> list) || list.isEmpty()) {
            return ApiResponse.ok(Map.of("translations", List.of()));
        }

        List<String> titles = list.stream()
                .limit(64)
                .map(value -> String.valueOf(value == null ? "" : value).trim())
                .toList();
        if (titles.stream().anyMatch(title -> title.isBlank() || title.length() > 500)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新闻标题长度必须为 1-500 字符");
        }
        List<String> missing = new ArrayList<>();
        for (String title : titles) {
            if (title.isBlank() || HAN_TEXT.matcher(title).find() || titleZhCache.containsKey(title)) continue;
            if (!missing.contains(title)) missing.add(title);
        }

        if (!missing.isEmpty()) {
            try {
                Long userId = null;
                if (aiRateLimitService != null) {
                    userId = CurrentUser.id();
                    aiRateLimitService.consume(userId);
                }
                Map<String, Object> translated = aiProxyService.post(
                        "/api/ai/news/translate", Map.of("titles", missing));
                if (aiRateLimitService != null && userId != null) {
                    aiRateLimitService.recordTokens(userId, translated);
                }
                Object rawData = translated.get("data");
                if (rawData instanceof Map<?, ?> data
                        && data.get("translations") instanceof List<?> translations
                        && translations.size() == missing.size()) {
                    for (int i = 0; i < missing.size(); i++) {
                        String value = String.valueOf(translations.get(i)).trim();
                        if (!value.isBlank()) titleZhCache.put(missing.get(i), value);
                    }
                }
            } catch (Exception ignored) {
                // 翻译失败只回退原文，不影响市场要闻本身。
            }
        }

        List<String> translations = titles.stream()
                .map(title -> title.isBlank() || HAN_TEXT.matcher(title).find()
                        ? title
                        : titleZhCache.getOrDefault(title, title))
                .toList();
        return ApiResponse.ok(Map.of("translations", translations));
    }

    /**
     * /daily 只命中内存缓存，不调用 AI，确保市场要闻主请求延迟只取决于 RSS。
     */
    private Map<String, Object> localizeCachedTitles(Map<String, Object> shaped) {
        Object rawItems = shaped.get("items");
        if (!(rawItems instanceof List<?> rawList) || rawList.isEmpty()) return shaped;

        List<Map<String, Object>> items = new ArrayList<>();
        for (Object raw : rawList) {
            if (!(raw instanceof Map<?, ?> source)) continue;
            Map<String, Object> item = new LinkedHashMap<>();
            source.forEach((key, value) -> item.put(String.valueOf(key), value));
            String original = String.valueOf(item.getOrDefault("title", "")).trim();
            item.put("title_original", original);

            if (HAN_TEXT.matcher(original).find()) {
                item.put("title_zh", original);
            } else {
                item.put("title_zh", titleZhCache.getOrDefault(original, original));
            }
            items.add(item);
        }

        Map<String, Object> out = new LinkedHashMap<>(shaped);
        out.put("items", items);
        return out;
    }
}
