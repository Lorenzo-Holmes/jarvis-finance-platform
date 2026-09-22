package com.jarvis.research.market.provider;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YahooGlobalIndexKlineTest {

    @Test
    void dailyIndexKlineUsesTheSelectedYahooTicker() {
        String response = """
                {"chart":{"result":[{"timestamp":[1789948800],"indicators":{"quote":[{"open":[5000],
                "close":[5010],"high":[5020],"low":[4990],"volume":[100]}]}}]}}
                """;
        AtomicReference<URI> requestedUri = new AtomicReference<>();
        WebClient client = WebClient.builder().exchangeFunction(request -> {
            requestedUri.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "application/json")
                    .body(response)
                    .build());
        }).build();
        YahooMarketDataProvider provider = new YahooMarketDataProvider(client);

        List<Map<String, Object>> rows = provider.kline("global_index", "^GSPC", "1d", 1);

        assertEquals(1, rows.size());
        assertEquals(5010.0, rows.get(0).get("close"));
        assertTrue(requestedUri.get().getPath().endsWith("/^GSPC"));
        assertTrue(requestedUri.get().getRawQuery().contains("range=1y"));
        assertTrue(requestedUri.get().getRawQuery().contains("interval=1d"));
    }
}
