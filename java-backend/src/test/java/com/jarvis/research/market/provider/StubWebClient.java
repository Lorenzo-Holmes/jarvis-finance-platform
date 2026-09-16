package com.jarvis.research.market.provider;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 给 Provider 用的 WebClient 桩：返回固定报文，并记录每一次请求的 URL。
 *
 * <p>为什么需要它：Provider 自己建 WebClient，所以此前**请求参数与解析完全没有测试**——
 * 而 {@code klt}、{@code secid}、{@code range} 这类参数传错了不会报错，
 * 只会静默换一种数据回来。这是本次重构留下的最大空洞。</p>
 *
 * <p>用 {@link WebClient.Builder#exchangeFunction} 实现：不需要新依赖，也不起服务器，
 * 而且请求会把 URL 原样落到 {@link #urls()}，于是"发对了请求"和"解析对了报文"
 * 可以在同一条测试里一起断言。</p>
 */
final class StubWebClient {

    private final List<String> urls = new ArrayList<>();
    private final WebClient client;

    private StubWebClient(String body, boolean ok) {
        this.client = WebClient.builder()
                .exchangeFunction(request -> {
                    urls.add(request.url().toString());
                    ClientResponse response = ClientResponse
                            .create(ok ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body(body == null ? "" : body)
                            .build();
                    return Mono.just(response);
                })
                .build();
    }

    /** 正常返回给定报文。 */
    static StubWebClient serving(String body) {
        return new StubWebClient(body, true);
    }

    /** 上游 5xx。用于验证 Provider 不会把错误报文当数据。 */
    static StubWebClient failing(String body) {
        return new StubWebClient(body, false);
    }

    WebClient client() {
        return client;
    }

    /** 全部请求 URL，按发生顺序。 */
    List<String> urls() {
        return Collections.unmodifiableList(urls);
    }

    String lastUrl() {
        return urls.isEmpty() ? null : urls.get(urls.size() - 1);
    }

    /** 请求次数。0 表示"根本没联网"——用于断言早退路径。 */
    int requestCount() {
        return urls.size();
    }
}