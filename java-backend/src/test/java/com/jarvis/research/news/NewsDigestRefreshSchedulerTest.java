package com.jarvis.research.news;

import com.jarvis.research.service.AiProxyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsDigestRefreshSchedulerTest {

    @Mock
    private AiProxyService aiProxyService;

    @Test
    void refreshesTheGlobalDigestWithoutAUserContext() {
        when(aiProxyService.post(any(), any())).thenReturn(Map.of(
                "refreshed", 10,
                "ok_sources", 10,
                "total_sources", 10,
                "sources", List.of(),
                "articles", List.of(Map.of("title", "市场快讯", "source_id", "source"))));

        new NewsDigestRefreshScheduler(aiProxyService).refresh();

        verify(aiProxyService).post(NewsDigestRefreshScheduler.DIGEST_PATH, Map.of());
    }

    @Test
    void keepsTheServiceHealthyWhenTheUpstreamIsUnavailable() {
        when(aiProxyService.post(any(), any())).thenThrow(new IllegalStateException("upstream timeout"));

        assertDoesNotThrow(() -> new NewsDigestRefreshScheduler(aiProxyService).refresh());
        verify(aiProxyService).post(NewsDigestRefreshScheduler.DIGEST_PATH, Map.of());
    }
}
