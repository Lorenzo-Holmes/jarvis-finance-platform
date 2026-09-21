package com.jarvis.research.news;

import com.jarvis.research.service.AiProxyService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NewsSourceServiceTest {

    @Test
    void filterDigestUsesOrWithinDimensionAndAcrossDimensions() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        NewsSourceRepository sources = mock(NewsSourceRepository.class);
        AiProxyService aiProxy = mock(AiProxyService.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L))
                .thenReturn(List.of(
                        NewsSubscription.builder().userId(42L).sourceKey("wire").topic("").enabled(true).build(),
                        NewsSubscription.builder().userId(42L).sourceKey("").topic("markets").enabled(true).build()));

        NewsSourceService service = new NewsSourceService(sources, subscriptions, aiProxy);
        Map<String, Object> result = service.filterDigest(42L, Map.of(
                "available", true,
                "items", List.of(
                        Map.of("source_id", "wire", "category", "crypto"),
                        Map.of("source_id", "wire", "category", "markets"),
                        Map.of("source_id", "other", "category", "markets"),
                        Map.of("source_id", "other", "category", "policy"))));

        assertEquals(1, ((List<?>) result.get("items")).size());
        assertEquals("wire", ((Map<?, ?>) ((List<?>) result.get("items")).get(0)).get("source_id"));
        assertEquals("markets", ((Map<?, ?>) ((List<?>) result.get("items")).get(0)).get("category"));
    }

    @Test
    void filterDigestMatchesAnyAggregatedSourceId() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L))
                .thenReturn(List.of(
                        NewsSubscription.builder().userId(42L).sourceKey("wire-b").topic("").enabled(true).build()));

        NewsSourceService service = new NewsSourceService(
                mock(NewsSourceRepository.class), subscriptions, mock(AiProxyService.class));
        Map<String, Object> result = service.filterDigest(42L, Map.of(
                "available", true,
                "items", List.of(Map.of(
                        "source_id", "wire-a",
                        "source_ids", List.of("wire-a", "wire-b"),
                        "category", "markets"))));

        assertEquals(1, ((List<?>) result.get("items")).size());
    }

    @Test
    void filterDigestExposesCandidateReductionStats() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L))
                .thenReturn(List.of(
                        NewsSubscription.builder().userId(42L).sourceKey("wire").topic("").enabled(true).build()));
        NewsSourceService service = new NewsSourceService(
                mock(NewsSourceRepository.class), subscriptions, mock(AiProxyService.class));

        Map<String, Object> result = service.filterDigest(42L, Map.of(
                "items", List.of(
                        Map.of("source_id", "wire", "category", "markets"),
                        Map.of("source_id", "other", "category", "markets"))));

        Map<?, ?> stats = (Map<?, ?>) result.get("filter_stats");
        assertEquals(2, stats.get("filter_before_count"));
        assertEquals(1, stats.get("filter_after_count"));
    }

    @Test
    void emptySubscriptionsLeaveDigestUntouched() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L)).thenReturn(List.of());
        NewsSourceService service = new NewsSourceService(
                mock(NewsSourceRepository.class), subscriptions, mock(AiProxyService.class));
        Map<String, Object> digest = Map.of("available", true, "items", List.of(Map.of("title", "all")));

        assertEquals(digest, service.filterDigest(42L, digest));
    }

    @Test
    void rankingQueryUsesTopicIntentWithoutInventingSourceMeaning() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L))
                .thenReturn(List.of(
                        NewsSubscription.builder().userId(42L).sourceKey("wire").topic("").enabled(true).build(),
                        NewsSubscription.builder().userId(42L).sourceKey("").topic("gold").enabled(true).build(),
                        NewsSubscription.builder().userId(42L).sourceKey("").topic("global").enabled(true).build()));
        NewsSourceService service = new NewsSourceService(
                mock(NewsSourceRepository.class), subscriptions, mock(AiProxyService.class));

        String query = service.rankingQuery(42L);

        org.junit.jupiter.api.Assertions.assertTrue(query.contains("黄金"));
        org.junit.jupiter.api.Assertions.assertTrue(query.contains("全球宏观"));
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("wire"));
    }

    @Test
    void rankingQueryFallsBackToGenericFinanceIntentWhenOnlySourcesAreSelected() {
        NewsSubscriptionRepository subscriptions = mock(NewsSubscriptionRepository.class);
        when(subscriptions.findByUserIdAndEnabledTrueOrderBySourceKeyAscTopicAsc(42L))
                .thenReturn(List.of(
                        NewsSubscription.builder().userId(42L).sourceKey("wire").topic("").enabled(true).build()));
        NewsSourceService service = new NewsSourceService(
                mock(NewsSourceRepository.class), subscriptions, mock(AiProxyService.class));

        String query = service.rankingQuery(42L);

        org.junit.jupiter.api.Assertions.assertTrue(query.contains("财经投研重要资讯"));
        org.junit.jupiter.api.Assertions.assertFalse(query.contains("wire"));
    }
}
