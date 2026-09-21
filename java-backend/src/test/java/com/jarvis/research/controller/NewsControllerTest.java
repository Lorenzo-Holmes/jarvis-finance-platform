package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.news.NewsSourceService;
import com.jarvis.research.service.AiProxyService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsControllerTest {

    private static Map<String, Object> digest(String title) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generated_at", "2026-09-19T10:00:00");
        raw.put("total_sources", 1);
        raw.put("ok_sources", 1);
        raw.put("sources", List.of(Map.of("source_id", "wire", "name", "Wire")));
        raw.put("articles", List.of(Map.of(
                "title", title,
                "url", "https://example.com/1",
                "source_id", "wire",
                "published", "2026-09-19T09:00:00Z")));
        return raw;
    }

    private static Map<String, Object> digestMany(String... titles) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generated_at", "2026-09-19T10:00:00");
        raw.put("total_sources", 1);
        raw.put("ok_sources", 1);
        raw.put("sources", List.of(Map.of("source_id", "wire", "name", "Wire")));
        List<Map<String, Object>> articles = new ArrayList<>();
        for (int index = 0; index < titles.length; index++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("title", titles[index]);
            item.put("url", "https://example.com/" + (index + 1));
            item.put("source_id", "wire");
            item.put("published", "2026-09-19T09:0" + index + ":00Z");
            item.put("rank_score", 80 - index);
            articles.add(item);
        }
        raw.put("articles", articles);
        return raw;
    }

    private static final class StubProxy extends AiProxyService {
        private final Map<String, Object> digest;
        private final List<String> translations;
        private final boolean failTranslation;
        private int translationCalls;
        private int analysisCalls;
        private int rerankCalls;
        private int rerankCandidateCount;

        StubProxy(Map<String, Object> digest, List<String> translations, boolean failTranslation) {
            super(new JarvisProperties());
            this.digest = digest;
            this.translations = translations;
            this.failTranslation = failTranslation;
        }

        @Override
        public Map<String, Object> post(String path, Object body) {
            if (path.startsWith("/internal/rss/digest")) return digest;
            if (path.equals("/api/ai/news/translate")) {
                translationCalls += 1;
                if (failTranslation) throw new IllegalStateException("translation unavailable");
                return Map.of("code", 200, "data", Map.of("translations", translations));
            }
            if (path.equals("/api/ai/analyze/news")) {
                analysisCalls += 1;
                return Map.of("code", 200, "data", Map.of(
                        "model", "test-model",
                        "analyses", List.of(Map.of("key", "wire|https://example.com/1", "sentiment", "positive"))));
            }
            if (path.equals("/internal/rss/rerank")) {
                rerankCalls += 1;
                Map<?, ?> payload = (Map<?, ?>) body;
                List<?> items = (List<?>) payload.get("articles");
                rerankCandidateCount = items.size();
                List<Object> reversed = new ArrayList<>(items);
                java.util.Collections.reverse(reversed);
                return Map.of(
                        "available", true,
                        "items", reversed,
                        "ranking_stage", "embedding_mmr",
                        "embedding_model", "test-embed",
                        "rerank_model", "");
            }
            throw new IllegalArgumentException("unexpected path: " + path);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> firstItem(ApiResponse<Object> response) {
        Map<String, Object> data = (Map<String, Object>) response.getData();
        return ((List<Map<String, Object>>) data.get("items")).get(0);
    }

    @SuppressWarnings("unchecked")
    private static List<String> translations(ApiResponse<Object> response) {
        Map<String, Object> data = (Map<String, Object>) response.getData();
        return (List<String>) data.get("translations");
    }

    @Test
    void dailyNeverBlocksOnTranslationAndPreservesOriginal() {
        StubProxy proxy = new StubProxy(
                digest("Fed sends a new signal"),
                List.of("美联储释放新信号"),
                false);

        Map<String, Object> item = firstItem(new NewsController(proxy).daily(12, true, false));

        assertEquals("Fed sends a new signal", item.get("title_original"));
        assertEquals("Fed sends a new signal", item.get("title_zh"));
        assertEquals(0, proxy.translationCalls);
    }

    @Test
    void translateReturnsChineseTitleAndWarmsDailyCache() {
        StubProxy proxy = new StubProxy(
                digest("Fed sends a new signal"),
                List.of("美联储释放新信号"),
                false);
        NewsController controller = new NewsController(proxy);

        ApiResponse<Object> translated = controller.translate(Map.of(
                "titles", List.of("Fed sends a new signal")));

        assertEquals(List.of("美联储释放新信号"), translations(translated));
        assertEquals(1, proxy.translationCalls);
        assertEquals("美联储释放新信号",
                firstItem(controller.daily(12, false, false)).get("title_zh"));
    }

    @Test
    void translationFailureFallsBackToOriginalWithoutDroppingNews() {
        StubProxy proxy = new StubProxy(
                digest("Market closes higher"),
                List.of(),
                true);
        NewsController controller = new NewsController(proxy);

        ApiResponse<Object> translated = controller.translate(Map.of(
                "titles", List.of("Market closes higher")));

        assertEquals(List.of("Market closes higher"), translations(translated));
        assertEquals("Market closes higher",
                firstItem(controller.daily(12, false, false)).get("title_zh"));
    }

    @Test
    void existingChineseTitleSkipsTranslation() {
        StubProxy proxy = new StubProxy(
                digest("央行发布最新数据"),
                List.of(),
                false);
        NewsController controller = new NewsController(proxy);

        ApiResponse<Object> translated = controller.translate(Map.of(
                "titles", List.of("央行发布最新数据")));

        assertEquals(List.of("央行发布最新数据"), translations(translated));
        assertEquals(0, proxy.translationCalls);
    }

    @SuppressWarnings("unchecked")
    @Test
    void analyzeSanitizesArticlesAndReturnsModelPayload() {
        StubProxy proxy = new StubProxy(digest("Market closes higher"), List.of(), false);
        NewsController controller = new NewsController(proxy);

        ApiResponse<Object> response = controller.analyze(Map.of("items", List.of(Map.of(
                "title", "Market closes higher",
                "summary", "summary",
                "url", "https://example.com/1",
                "source_id", "wire"))));

        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertEquals("test-model", data.get("model"));
        assertEquals(1, ((List<?>) data.get("analyses")).size());
        assertEquals(1, proxy.analysisCalls);
    }

    @SuppressWarnings("unchecked")
    @Test
    void authenticatedDailyFiltersThenSemanticReranksThenAppliesFinalLimit() {
        StubProxy proxy = new StubProxy(digestMany("A", "B", "C"), List.of(), false);
        NewsSourceService sourceService = mock(NewsSourceService.class);
        when(sourceService.rankingQuery(42L)).thenReturn("黄金市场");
        when(sourceService.filterDigest(eq(42L), anyMap())).thenAnswer(invocation -> {
            Map<String, Object> shaped = invocation.getArgument(1);
            List<Map<String, Object>> items = (List<Map<String, Object>>) shaped.get("items");
            Map<String, Object> filtered = new LinkedHashMap<>(shaped);
            filtered.put("items", new ArrayList<>(items.subList(1, items.size())));
            return filtered;
        });

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of()));
        try {
            NewsController controller = new NewsController(proxy, null, sourceService);
            ApiResponse<Object> response = controller.daily(1, false, false);
            Map<String, Object> data = (Map<String, Object>) response.getData();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");

            assertEquals(1, items.size());
            assertEquals("C", items.get(0).get("title"));
            assertEquals(1, data.get("returned_count"));
            assertEquals(1, proxy.rerankCalls);
            assertEquals(2, proxy.rerankCandidateCount);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @SuppressWarnings("unchecked")
    @Test
    void latestRankingSkipsSemanticRerankAndUsesRecency() {
        Map<String, Object> raw = digestMany("A", "B", "C");
        List<Map<String, Object>> articles = (List<Map<String, Object>>) raw.get("articles");
        articles.get(0).put("recency_timestamp", 100.0);
        articles.get(1).put("recency_timestamp", 300.0);
        articles.get(2).put("recency_timestamp", 200.0);
        StubProxy proxy = new StubProxy(raw, List.of(), false);
        NewsSourceService sourceService = mock(NewsSourceService.class);
        when(sourceService.filterDigest(eq(42L), anyMap())).thenAnswer(invocation -> invocation.getArgument(1));

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(42L, null, List.of()));
        try {
            NewsController controller = new NewsController(proxy, null, sourceService);
            ApiResponse<Object> response = controller.daily(3, false, false, "latest");
            Map<String, Object> data = (Map<String, Object>) response.getData();
            List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("items");

            assertEquals(List.of("B", "C", "A"),
                    items.stream().map(item -> String.valueOf(item.get("title"))).toList());
            assertEquals(0, proxy.rerankCalls);
            assertEquals("latest", data.get("rank_mode"));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}
