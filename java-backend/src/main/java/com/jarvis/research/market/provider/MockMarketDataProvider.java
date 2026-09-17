package com.jarvis.research.market.provider;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 测试环境行情 Provider。
 *
 * 用于自动化测试时避免依赖第三方行情接口。
 */
@Component
@Profile("test")
public class MockMarketDataProvider implements MarketDataProvider {

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public boolean supports(String market) {
        return "mock".equalsIgnoreCase(market);
    }

    @Override
    public Map<String, Object> quote(String symbol) {
        return Map.of(
                "symbol", symbol,
                "price", 100.0,
                "source", name()
        );
    }

    @Override
    public List<Map<String, Object>> kline(String symbol, String interval, int limit) {
        return List.of(Map.of(
                "symbol", symbol,
                "interval", interval,
                "close", 100.0
        ));
    }
}
