package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.service.AiProxyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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

    private static final class StubProxy extends AiProxyService {
        private final Map<String, Object> digest;
        private final List<String> translations;
        private final boolean failTranslation;
        private int translationCalls;
        private int analysisCalls;

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
}
