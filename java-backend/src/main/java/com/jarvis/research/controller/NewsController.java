package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.news.NewsSourceService;
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
    private final NewsSourceService newsSourceService;
    private final Map<String, String> titleZhCache = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_TRANSLATION_CACHE;
                }
            });

    @Autowired
    public NewsController(AiProxyService aiProxyService, AiRateLimitService aiRateLimitService,
                          NewsSourceService newsSourceService) {
        this.aiProxyService = aiProxyService;
        this.aiRateLimitService = aiRateLimitService;
        this.newsSourceService = newsSourceService;
    }

    /** 兼容不加载 Spring 容器的轻量单元测试。 */
    public NewsController(AiProxyService aiProxyService) {
        this(aiProxyService, null, null);
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
                                     @RequestParam(defaultValue = "false") boolean force,
                                     @RequestParam(defaultValue = "smart") String ranking) {
        String rankingMode = ranking == null ? "smart" : ranking.trim().toLowerCase();
        if (!rankingMode.equals("smart") && !rankingMode.equals("latest")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ranking 仅支持 smart/latest");
        }
        String path = "/internal/rss/digest?refresh=" + refresh + "&force=" + force;
        try {
            Map<String, Object> digest = aiProxyService.post(path, Map.of());
            Map<String, Object> shaped = NewsDigest.fromDigest(digest, 0);
            if (newsSourceService != null && CurrentUser.isAuthenticated()) {
                Long userId = CurrentUser.id();
                shaped = newsSourceService.filterDigest(userId, shaped);
                if (rankingMode.equals("smart")) {
                    shaped = semanticRerank(shaped, newsSourceService.rankingQuery(userId));
                }
            }
            if (rankingMode.equals("latest")) shaped = NewsDigest.orderByRecency(shaped);
            shaped = NewsDigest.limitItems(shaped, limit);
            Map<String, Object> withCount = new LinkedHashMap<>(shaped);
            Object finalItems = shaped.get("items");
            withCount.put("returned_count", finalItems instanceof List<?> list ? list.size() : 0);
            shaped = withCount;
            return ApiResponse.ok(localizeCachedTitles(shaped));
        } catch (Exception e) {
            return ApiResponse.ok(NewsDigest.unavailable(NewsDigest.REASON_UNAVAILABLE));
        }
    }

    /** 兼容现有直接调用单元测试与内部调用方。 */
    public ApiResponse<Object> daily(int limit, boolean refresh, boolean force) {
        return daily(limit, refresh, force, "smart");
    }

    /**
     * 对当前资讯批量生成可审计的摘要、关键词、情绪和市场影响。
     *
     * <p>RSS 抓取与规则方向仍然独立可用；模型分析是增强步骤，配额与 usage
     * 统一在 Java 边界处理，浏览器不能直接访问 Python。</p>
     */
    @PostMapping("/analyze")
    public ApiResponse<Object> analyze(@RequestBody(required = false) Map<String, Object> body) {
        Object rawItems = body == null ? null : body.getOrDefault("items", body.get("articles"));
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "至少选择一条资讯进行分析");
        }

        List<Map<String, Object>> articles = new ArrayList<>();
        for (Object raw : list) {
            if (!(raw instanceof Map<?, ?> source)) continue;
            String title = text(source.get("title"));
            if (title.isBlank()) continue;
            String sourceId = text(source.get("source_id"));
            String url = text(source.get("url"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("key", sourceId + "|" + url);
            item.put("title", trim(title, 500));
            Object bodyValue = source.get("body");
            if (bodyValue == null) bodyValue = source.get("summary");
            Object sourceValue = source.get("source");
            if (sourceValue == null) sourceValue = sourceId;
            item.put("body", trim(text(bodyValue), 2_000));
            item.put("source", trim(text(sourceValue), 120));
            item.put("url", trim(url, 500));
            articles.add(item);
            if (articles.size() >= 12) break;
        }
        if (articles.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "没有可分析的资讯标题");
        }

        Long userId = null;
        try {
            if (aiRateLimitService != null) {
                userId = CurrentUser.id();
                aiRateLimitService.consume(userId);
            }
            Map<String, Object> response = aiProxyService.post(
                    "/api/ai/analyze/news", Map.of("articles", articles));
            if (aiRateLimitService != null && userId != null) {
                aiRateLimitService.recordTokens(userId, response);
            }
            Object data = response == null ? null : response.get("data");
            return ApiResponse.ok(data == null ? Map.of("analyses", List.of()) : data);
        } catch (RuntimeException error) {
            // 配额已消费时由配额服务保持既有语义；模型失败直接返回可理解错误，
            // 前端继续展示已经抓到的 RSS 与规则分析，不把资讯流清空。
            throw error;
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

    /**
     * 语义排序是纯增强：Python embedding/rerank 未部署、超时或格式异常时必须保持
     * V1 intelligence 顺序，不允许把每日要闻整体降级为 unavailable。
     */
    private Map<String, Object> semanticRerank(Map<String, Object> shaped, String query) {
        if (shaped == null || query == null || query.isBlank()) return shaped;
        Object rawItems = shaped.get("items");
        if (!(rawItems instanceof List<?> list) || list.isEmpty()) return shaped;

        int candidateCount = Math.min(60, list.size());
        List<Object> candidates = new ArrayList<>(list.subList(0, candidateCount));
        try {
            Map<String, Object> response = aiProxyService.post(
                    "/internal/rss/rerank",
                    Map.of("query", query, "articles", candidates));
            if (response == null || !Boolean.TRUE.equals(response.get("available"))) return shaped;
            Object rankedRaw = response.get("items");
            if (!(rankedRaw instanceof List<?> ranked) || ranked.size() != candidateCount) return shaped;

            List<Object> items = new ArrayList<>(ranked);
            if (candidateCount < list.size()) items.addAll(list.subList(candidateCount, list.size()));
            Map<String, Object> out = new LinkedHashMap<>(shaped);
            out.put("items", items);
            out.put("semantic_ranking", Map.of(
                    "stage", text(response.get("ranking_stage")),
                    "embedding_model", text(response.get("embedding_model")),
                    "rerank_model", text(response.get("rerank_model"))));
            return out;
        } catch (Exception ignored) {
            return shaped;
        }
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String trim(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
