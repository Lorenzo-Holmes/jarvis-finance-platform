package com.jarvis.research.market.provider;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 给 Provider 用的 WebClient 桩：返回固定报文，并记录每一次请求的 URL。
 *
 * <p>为什么需要它：Provider 自己建 WebClient，所以此前**请求参数与解析完全没有测试**——
 * 而 {@code klt}、{@code secid}、{@code range} 这类参数传错了不会报错，
 * 只会静默换一种数据回来。</p>
 *
 * <p>用 {@link WebClient.Builder#exchangeFunction} 实现：不需要新依赖，也不起服务器，
 * 而且请求会把 URL 原样落到 {@link #urls()}，于是"发对了请求"和"解析对了报文"
 * 可以在同一条测试里一起断言。</p>
 *
 * <p><b>报文一律以字节下发</b>（{@link #body(Flux)} 而不是 {@code body(String)}）。
 * 曾经用字符串下发并声明 charset，结果 Spring 是按**平台默认字符集**编码的——
 * 这台机器默认 GBK，于是"按 ISO-8859-1 包成字符串再声明 charset"的无损技巧
 * 反而把 GBK 报文重编了一遍，测出来的不是真实解码路径。给字节就不会有这个问题。</p>
 */
final class StubWebClient {

    private static final DefaultDataBufferFactory BUFFERS = new DefaultDataBufferFactory();

    private final List<String> urls = new ArrayList<>();
    private final WebClient client;

    private StubWebClient(byte[] body, boolean ok, String contentType) {
        byte[] payload = body == null ? new byte[0] : body;
        this.client = WebClient.builder()
                .exchangeFunction(request -> {
                    urls.add(request.url().toString());
                    ClientResponse response = ClientResponse
                            .create(ok ? HttpStatus.OK : HttpStatus.INTERNAL_SERVER_ERROR)
                            .header(HttpHeaders.CONTENT_TYPE, contentType)
                            .body(Flux.just(BUFFERS.wrap(payload)))
                            .build();
                    return Mono.just(response);
                })
                .build();
    }

    /** 正常返回给定报文（按 UTF-8 编码，并声明 UTF-8）。 */
    static StubWebClient serving(String body) {
        return new StubWebClient(body == null ? null : body.getBytes(StandardCharsets.UTF_8), true,
                "application/json;charset=UTF-8");
    }

    /**
     * 按**原始字节**返回报文，不做任何字符集转换。
     *
     * <p>腾讯的接口是 GBK 编码的，Provider 用 {@code new String(bytes, GBK)} 解码。
     * 只有原样下发字节，才测得到真实的解码路径。</p>
     */
    static StubWebClient servingBytes(byte[] body) {
        return new StubWebClient(body, true, "application/octet-stream");
    }

    /** 上游 5xx。用于验证 Provider 不会把错误报文当数据。 */
    static StubWebClient failing(String body) {
        return new StubWebClient(body == null ? null : body.getBytes(StandardCharsets.UTF_8), false,
                "application/json;charset=UTF-8");
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