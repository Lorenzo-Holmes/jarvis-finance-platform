package com.jarvis.research.admin;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.news.NewsDtos.SourceRequest;
import com.jarvis.research.news.NewsSourceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理员维护 RSS 来源；/api/admin/** 的 ADMIN 角色由 SecurityConfig 统一保护。 */
@RestController
@RequestMapping("/api/admin/news/sources")
@RequiredArgsConstructor
public class NewsSourceAdminController {

    private final NewsSourceService newsSourceService;

    @GetMapping
    public ApiResponse<?> all() {
        return ApiResponse.ok(Map.of("items", newsSourceService.listSources(false)));
    }

    @PostMapping
    public ApiResponse<?> create(@Valid @RequestBody SourceRequest body) {
        return ApiResponse.ok(newsSourceService.createSource(body));
    }

    @PatchMapping("/{sourceKey}")
    public ApiResponse<?> update(@PathVariable String sourceKey,
                                 @Valid @RequestBody SourceRequest body) {
        return ApiResponse.ok(newsSourceService.updateSource(sourceKey, body));
    }
}
