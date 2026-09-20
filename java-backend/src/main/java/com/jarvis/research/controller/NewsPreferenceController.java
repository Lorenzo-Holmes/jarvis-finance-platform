package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.news.NewsDtos.SubscriptionRequest;
import com.jarvis.research.news.NewsSourceService;
import com.jarvis.research.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 用户可见的资讯源目录与个性化订阅。 */
@RestController
@RequestMapping("/api/news")
@RequiredArgsConstructor
public class NewsPreferenceController {

    private final NewsSourceService newsSourceService;

    @GetMapping("/sources")
    public ApiResponse<?> sources() {
        return ApiResponse.ok(Map.of("items", newsSourceService.listSources(true)));
    }

    @GetMapping("/subscriptions")
    public ApiResponse<?> subscriptions() {
        return ApiResponse.ok(newsSourceService.subscriptions(CurrentUser.id()));
    }

    @PutMapping("/subscriptions")
    public ApiResponse<?> replace(@Valid @RequestBody SubscriptionRequest body) {
        return ApiResponse.ok(newsSourceService.replaceSubscriptions(CurrentUser.id(), body));
    }
}
