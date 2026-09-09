package com.jarvis.research.controller;

import com.jarvis.research.common.ApiResponse;
import com.jarvis.research.market.MarketPreferenceDtos.PreferenceView;
import com.jarvis.research.market.MarketPreferenceDtos.UpdateRequest;
import com.jarvis.research.market.MarketPreferenceService;
import com.jarvis.research.security.CurrentUser;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 登录用户的多市场自选与默认标的偏好。 */
@RestController
@RequestMapping("/api/market/preferences")
@RequiredArgsConstructor
public class MarketPreferenceController {

    private final MarketPreferenceService service;

    @GetMapping
    public ApiResponse<PreferenceView> get() {
        return ApiResponse.ok(service.get(CurrentUser.id()));
    }

    /** 全量替换，保证新增、编辑、删除在同一事务中保持一致。 */
    @PutMapping
    public ApiResponse<PreferenceView> replace(@Valid @RequestBody UpdateRequest request) {
        return ApiResponse.ok(service.replace(CurrentUser.id(), request), "标的偏好已保存");
    }
}
