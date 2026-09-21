package com.jarvis.research.news;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 每日要闻整形测试。
 *
 * <p>夹具全部手写、期望值手算：只断言"输入这样、输出必须那样"，
 * 不从实现里抄结果，否则测试只是同义反复。
 */
class NewsDigestTest {

    private static Map<String, Object> digestOf(Object sources, Object articles) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generated_at", "2026-09-17T08:30:00");
        raw.put("total_sources", 2);
        raw.put("ok_sources", 1);
        raw.put("sources", sources);
        raw.put("articles", articles);
        return raw;
    }

    private static Map<String, Object> source(String id, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("source_id", id);
        item.put("name", name);
        return item;
    }

    private static Map<String, Object> article(String title, String url, String sourceId, String published) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("title", title);
        item.put("url", url);
        item.put("source_id", sourceId);
        item.put("published", published);
        return item;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> shaped) {
        return (List<Map<String, Object>>) shaped.get("items");
    }

    @Test
    void unavailableEnvelopeKeepsStableShape() {
        Map<String, Object> out = NewsDigest.unavailable(null);

        assertEquals(false, out.get("available"));
        assertEquals(NewsDigest.REASON_UNAVAILABLE, out.get("reason"));
        assertEquals(List.of(), out.get("items"));
        assertEquals(0, out.get("total_sources"));
        assertEquals(0, out.get("ok_sources"));
    }

    @Test
    void emptyOrMissingPayloadIsUnavailableNotError() {
        // 显式给出泛型：List.of(Map.of(), new LinkedHashMap<>()) 会被推断成
        // Map<? extends Object,Object>，没法赋给 Map<String,Object>。
        for (Map<String, Object> raw : List.<Map<String, Object>>of(new LinkedHashMap<>())) {
            Map<String, Object> out = NewsDigest.fromDigest(raw, 10);
            assertEquals(false, out.get("available"));
            assertEquals(NewsDigest.REASON_EMPTY, out.get("reason"));
        }
        Map<String, Object> nulled = NewsDigest.fromDigest(null, 10);
        assertEquals(false, nulled.get("available"));
        assertEquals(NewsDigest.REASON_EMPTY, nulled.get("reason"));
    }

    @Test
    void digestCarriesReadableSourceNamesAndPreservesOrder() {
        Map<String, Object> raw = digestOf(
                List.of(source("yahoo_finance", "Yahoo Finance"), source("cnbc_finance", "CNBC Finance")),
                List.of(
                        article("降准落地", "https://a.example.com/1", "yahoo_finance", "Wed, 17 Sep 2026 20:00:00 +0800"),
                        article("美股收高", "https://b.example.com/2", "cnbc_finance", "Wed, 17 Sep 2026 12:00:00 +0800")));

        Map<String, Object> out = NewsDigest.fromDigest(raw, 12);

        assertEquals(true, out.get("available"));
        assertNull(out.get("reason"));
        assertEquals("2026-09-17T08:30:00", out.get("generated_at"));
        assertEquals(2, out.get("total_sources"));
        assertEquals(1, out.get("ok_sources"));

        List<Map<String, Object>> shaped = items(out);
        assertEquals(2, shaped.size());
        // source_id 必须映射成人可读名称
        assertEquals("降准落地", shaped.get(0).get("title"));
        assertEquals("Yahoo Finance", shaped.get(0).get("source"));
        assertEquals("yahoo_finance", shaped.get(0).get("source_id"));
        assertEquals("https://a.example.com/1", shaped.get(0).get("url"));
        assertEquals("Wed, 17 Sep 2026 20:00:00 +0800", shaped.get(0).get("published"));
        // 顺序就是语义：不能被整形打乱
        assertEquals("美股收高", shaped.get(1).get("title"));
        assertEquals("CNBC Finance", shaped.get(1).get("source"));
    }

    @Test
    void limitTruncatesWithoutReordering() {
        List<Map<String, Object>> articles = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            articles.add(article("标题" + i, "https://a.example.com/" + i, "s", ""));
        }
        Map<String, Object> out = NewsDigest.fromDigest(digestOf(List.of(source("s", "S")), articles), 2);

        List<Map<String, Object>> shaped = items(out);
        assertEquals(2, shaped.size());
        assertEquals("标题1", shaped.get(0).get("title"));
        assertEquals("标题2", shaped.get(1).get("title"));
        // limit<=0 视为不截断
        assertEquals(5, items(NewsDigest.fromDigest(digestOf(List.of(source("s", "S")), articles), 0)).size());
    }

    @Test
    void limitItemsRunsAfterFilteringWithoutMutatingOriginalEnvelope() {
        Map<String, Object> shaped = new LinkedHashMap<>();
        shaped.put("available", true);
        shaped.put("items", List.of(Map.of("title", "A"), Map.of("title", "B"), Map.of("title", "C")));

        Map<String, Object> limited = NewsDigest.limitItems(shaped, 2);

        assertEquals(2, ((List<?>) limited.get("items")).size());
        assertEquals(true, limited.get("available"));
        assertEquals(3, ((List<?>) shaped.get("items")).size());
    }

    @Test
    void intelligenceFieldsArePassedThroughForFrontendAuditability() {
        Map<String, Object> intelligent = article("高质量资讯", "https://a.example.com/1", "s", "");
        intelligent.put("rank_score", 0.91);
        intelligent.put("source_ids", List.of("s", "wire"));
        intelligent.put("source_count", 2);
        intelligent.put("selection_reason", List.of("来源可信度 88", "2 个来源确认"));

        Map<String, Object> raw = digestOf(List.of(source("s", "S")), List.of(intelligent));
        raw.put("rank_mode", "intelligence_v1");
        Map<String, Object> shaped = NewsDigest.fromDigest(raw, 10);

        assertEquals(0.91, items(shaped).get(0).get("rank_score"));
        assertEquals(List.of("s", "wire"), items(shaped).get(0).get("source_ids"));
        assertEquals(2, items(shaped).get(0).get("source_count"));
        assertEquals("intelligence_v1", shaped.get("rank_mode"));
    }

    @Test
    void entriesWithoutTitleOrWithWrongTypeAreDropped() {
        List<Object> articles = new ArrayList<>();
        articles.add(article("", "https://a.example.com/1", "s", ""));
        articles.add(article("   ", "https://a.example.com/2", "s", ""));
        articles.add("not-a-map");
        articles.add(null);
        articles.add(article("保留", "https://a.example.com/3", "s", ""));

        Map<String, Object> out = NewsDigest.fromDigest(digestOf(List.of(source("s", "S")), articles), 10);

        List<Map<String, Object>> shaped = items(out);
        assertEquals(1, shaped.size());
        assertEquals("保留", shaped.get(0).get("title"));
    }

    @Test
    void unknownSourceFallsBackToIdAndBlankNameFallsBackToo() {
        Map<String, Object> raw = digestOf(
                List.of(source("known", ""), source("", "无 id 的源"), "not-a-map"),
                List.of(
                        article("未知源", "https://a.example.com/1", "mystery", ""),
                        article("空名源", "https://a.example.com/2", "known", "")));

        List<Map<String, Object>> shaped = items(NewsDigest.fromDigest(raw, 10));

        assertEquals("mystery", shaped.get(0).get("source"));
        assertEquals("known", shaped.get(1).get("source"));
    }

    @Test
    void malformedCountsAndListsDegradeInsteadOfThrowing() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("total_sources", "not-a-number");
        raw.put("ok_sources", null);
        raw.put("sources", "not-a-list");
        raw.put("articles", "not-a-list");

        Map<String, Object> out = NewsDigest.fromDigest(raw, 5);

        assertEquals(true, out.get("available"));
        assertEquals(0, out.get("total_sources"));
        assertEquals(0, out.get("ok_sources"));
        assertTrue(items(out).isEmpty());
        // 非列表的 sources 被规整成空列表（而不是把原始字符串透出去）
        assertEquals(List.of(), out.get("sources"));

        Map<String, Object> numericStrings = new LinkedHashMap<>();
        numericStrings.put("total_sources", "3");
        numericStrings.put("ok_sources", "2");
        Map<String, Object> parsed = NewsDigest.fromDigest(numericStrings, 5);
        assertEquals(3, parsed.get("total_sources"));
        assertEquals(2, parsed.get("ok_sources"));
        // 没有 articles 键时是空列表，而不是抛错
        assertTrue(items(parsed).isEmpty());
    }
}
