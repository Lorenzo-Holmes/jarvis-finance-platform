package com.jarvis.research.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.research.news.NewsDigest;
import com.jarvis.research.service.AiProxyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 每日资讯日报执行器的行为断言。
 *
 * <p>核心是三条边界：</p>
 * <ul>
 *   <li><b>拿不到资讯 ≠ 任务失败</b>：判失败的话，上游深夜维护连续 5 次就会把用户的任务
 *       自动暂停，而他完全不知道为什么。所以降级成 {@code available=false} 的摘要。</li>
 *   <li><b>"没有资讯"和"拿不到资讯"必须能区分</b>：前者是源都空，后者是链路不可用，
 *       两者的排障方向完全不同，摘要里不能都写成"完成"。</li>
 *   <li><b>产物不得撑爆列宽</b>：{@code artifacts_json} 是 {@code VARCHAR(4000)}，
 *       中文标题较长时超长会被内核截断成无法解析的垃圾。</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DailyDigestExecutorTest {

    /** 与 {@code NewsController} 一致：走代理层打 Python 的 digest 端点。 */
    private static final String DIGEST_PATH = "/internal/rss/digest?refresh=true&force=false";

    @Mock
    private AiProxyService aiProxyService;

    private DailyDigestExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DailyDigestExecutor(aiProxyService, new ObjectMapper());
    }

    @Test
    void claimsTheDailyDigestType() {
        assertEquals(ScheduledTaskType.DAILY_DIGEST, executor.type());
    }

    @Test
    void goesThroughTheProxyToTheDigestEndpoint() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(3));

        executor.execute(task("{}"));

        // 与 NewsController 同一入口、同一参数：参数与页面看到的数据源必须一致，
        // 否则"页面上的要闻"和"日报里的要闻"会是两批东西。
        verify(aiProxyService).post(DIGEST_PATH, Map.of());
    }

    @Test
    void theSummaryNamesSourcesAndHeadlines() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(5));

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("共 5 条"), summary);
        assertTrue(summary.contains("资讯源 2/2 可用"), summary);
        // 摘要必须自带内容 —— 只说"完成"等于没信息量，用户还得点开产物才知道抓到了什么。
        assertTrue(summary.contains("标题 1"), summary);
        assertTrue(summary.contains("路透"), summary);
    }

    @Test
    void theSummaryOnlyListsTheConfiguredNumberOfHeadlines() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(8));

        // 摘要列只有 1000 字符，标题列太多会把"来源覆盖情况"这个健康信号挤掉。
        String summary = executor.execute(task("{\"headlineCount\":1}")).summary();

        assertTrue(summary.contains("标题 1"), summary);
        assertFalse(summary.contains("标题 2"), summary);
        assertTrue(summary.contains("……"), "还有更多条时应给出省略提示：" + summary);
    }

    @Test
    void theLimitCapsHowManyArticlesAreKept() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(20));

        assertEquals(3, countInSummary(executor.execute(task("{\"limit\":3}")).summary()));
        // 默认 10 条。
        assertEquals(10, countInSummary(executor.execute(task("{}")).summary()));
    }

    @Test
    void anOutOfRangeLimitIsClampedInsteadOfFailingTheTask() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(50));

        // 用户填 999 想要的显然是"尽量多"，夹到上限比把任务判失败更符合意图。
        String summary = executor.execute(task("{\"limit\":999}")).summary();

        assertEquals(20, countInSummary(summary));
        assertTrue(summary.contains("已按上限 20 条截断"), summary);
    }

    @Test
    void anUnavailableUpstreamIsNotATaskFailure() {
        when(aiProxyService.post(anyString(), any()))
                .thenThrow(new IllegalStateException("Python AI 服务调用失败: connection refused"));

        TaskExecutionResult result = executor.execute(task("{}"));

        // 关键：不抛异常 = 内核记 SUCCESS，任务不会被连续失败自动暂停。
        assertNotNull(result);
        assertTrue(result.summary().contains("未能取到资讯"), result.summary());
        assertTrue(result.summary().contains(NewsDigest.REASON_UNAVAILABLE), result.summary());
    }

    @Test
    void anEmptyDigestIsReportedDifferentlyFromAnUnavailableOne() {
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("generated_at", "2026-09-20T08:00:00");
        empty.put("total_sources", 2);
        empty.put("ok_sources", 2);
        empty.put("sources", List.of());
        empty.put("articles", List.of());
        when(aiProxyService.post(anyString(), any())).thenReturn(empty);

        String summary = executor.execute(task("{}")).summary();

        // "源都空"与"链路不通"的排障方向完全不同，摘要不能都写成一句"完成"。
        assertTrue(summary.contains("没有取到任何带标题的资讯"), summary);
        assertFalse(summary.contains("未能取到资讯"), summary);
    }

    @Test
    void articlesWithoutTitlesAreDroppedRatherThanShownAsBlank() {
        // 混进去两条没有标题的：NewsDigest 会丢弃它们（没有标题无法展示），
        // 但剩下的两条仍应正常出现在摘要里，而不是整批被判成"没有资讯"。
        Map<String, Object> raw = digest(4);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articles = (List<Map<String, Object>>) raw.get("articles");
        articles.get(1).put("title", "   ");
        articles.get(2).remove("title");
        when(aiProxyService.post(anyString(), any())).thenReturn(raw);

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("共 2 条"), summary);
        assertFalse(summary.contains("没有取到任何带标题的资讯"), summary);
    }

    @Test
    void aDigestWithNoUsableTitlesAtAllIsReportedAsEmptyRatherThanUnavailable() {
        Map<String, Object> raw = digest(2);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> articles = (List<Map<String, Object>>) raw.get("articles");
        articles.forEach(article -> article.put("title", "  "));
        when(aiProxyService.post(anyString(), any())).thenReturn(raw);

        String summary = executor.execute(task("{}")).summary();

        assertTrue(summary.contains("没有取到任何带标题的资讯"), summary);
    }

    @Test
    void dirtyParamsFallBackToDefaults() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(20));

        assertEquals(10, countInSummary(executor.execute(task("{不是 JSON")).summary()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void artifactsCarryTheHeadlinesAndStayWithinTheColumnWidth() throws Exception {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(20));

        String artifacts = executor.execute(task("{}")).artifactsJson();
        assertNotNull(artifacts);
        Map<String, Object> parsed = new ObjectMapper().readValue(artifacts, Map.class);

        assertEquals(10, ((List<?>) parsed.get("items")).size());
        assertEquals(10, parsed.get("count"));
        assertEquals(2, parsed.get("ok_sources"));
        assertEquals("2026-09-20T08:00:00", parsed.get("generated_at"));

        Map<String, Object> first = ((List<Map<String, Object>>) parsed.get("items")).get(0);
        assertEquals("标题 1", first.get("title"));
        assertEquals("路透", first.get("source"));
        assertEquals("https://example.com/1", first.get("url"));

        assertTrue(artifacts.length() < 4000, "产物必须小于列宽，实际 " + artifacts.length());
    }

    @Test
    @SuppressWarnings("unchecked")
    void longTitlesStillStayWithinTheColumnWidth() throws Exception {
        // 中文标题很长时的最坏情况：产物必须在列宽内，否则会被内核截成无法解析的 JSON。
        Map<String, Object> raw = digest(20);
        List<Map<String, Object>> articles = (List<Map<String, Object>>) raw.get("articles");
        for (Map<String, Object> article : articles) {
            article.put("title", "标题".repeat(60));
            article.put("url", "https://example.com/" + "a".repeat(120));
        }
        when(aiProxyService.post(anyString(), any())).thenReturn(raw);

        String artifacts = executor.execute(task("{}")).artifactsJson();

        assertTrue(artifacts.length() < 4000, "长标题下产物也必须小于列宽，实际 " + artifacts.length());
        // 仍必须是可解析的 JSON（截断过的产物是解析不出来的）。
        new ObjectMapper().readValue(artifacts, Map.class);
    }

    @Test
    void noNotificationIsSentFromTheExecutor() {
        when(aiProxyService.post(anyString(), any())).thenReturn(digest(3));

        executor.execute(task("{}"));

        // 执行器只依赖代理层：提醒属于通知模块的职责（订阅内核广播的事件），
        // 这里若自己私接一个推送通道，用户就会收到双份提醒。
        verify(aiProxyService, never()).get(anyString());
        ArgumentCaptor<String> path = ArgumentCaptor.forClass(String.class);
        verify(aiProxyService).post(path.capture(), any());
        assertEquals(List.of(DIGEST_PATH), path.getAllValues());
    }

    /** 摘要里的总条数（"每日资讯日报：共 N 条"）。 */
    private static int countInSummary(String summary) {
        int start = summary.indexOf("共 ") + 2;
        int end = summary.indexOf(" 条", start);
        return Integer.parseInt(summary.substring(start, end).trim());
    }

    private static ScheduledTask task(String paramsJson) {
        return ScheduledTask.builder()
                .id(7L)
                .userId(42L)
                .name("每日资讯日报")
                .taskType(ScheduledTaskType.DAILY_DIGEST)
                .cronExpr("0 0 8 * * *")
                .timezone("Asia/Shanghai")
                .paramsJson(paramsJson)
                .status(ScheduledTaskStatus.ACTIVE)
                .consecutiveFailures(0)
                .build();
    }

    /** 与 Python {@code RSSStore.digest} 同构的载荷：2 个源、{@code count} 条文章。 */
    private static Map<String, Object> digest(int count) {
        List<Map<String, Object>> sources = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("source_id", "s" + i);
            source.put("name", "路透");
            source.put("ok", true);
            source.put("crawled", true);
            source.put("fetched", count);
            source.put("added", count);
            source.put("error", null);
            sources.add(source);
        }

        List<Map<String, Object>> articles = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Map<String, Object> article = new LinkedHashMap<>();
            article.put("title", "标题 " + i);
            article.put("url", "https://example.com/" + i);
            article.put("source_id", "s0");
            article.put("published", "2026-09-20T0" + (i % 10) + ":00:00");
            articles.add(article);
        }

        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("generated_at", "2026-09-20T08:00:00");
        raw.put("refreshed", 2);
        raw.put("ok_sources", 2);
        raw.put("total_sources", 2);
        raw.put("sources", sources);
        raw.put("articles", articles);
        return raw;
    }
}
