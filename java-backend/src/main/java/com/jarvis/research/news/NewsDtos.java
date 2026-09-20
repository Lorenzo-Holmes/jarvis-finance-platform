package com.jarvis.research.news;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

public final class NewsDtos {

    private NewsDtos() {}

    @Data
    public static class SourceRequest {
        @NotBlank(message = "sourceKey 不能为空")
        @Size(max = 80, message = "sourceKey 不能超过80个字符")
        @Pattern(regexp = "[A-Za-z0-9_-]+", message = "sourceKey 只能包含字母、数字、下划线和连字符")
        private String sourceKey;

        @NotBlank(message = "name 不能为空")
        @Size(max = 120, message = "来源名称不能超过120个字符")
        private String name;

        @NotBlank(message = "url 不能为空")
        @Size(max = 500, message = "RSS URL 不能超过500个字符")
        @Pattern(regexp = "https?://.+", message = "RSS URL 必须是 http(s) 地址")
        private String url;

        @Size(max = 40, message = "分类不能超过40个字符")
        private String category = "general";

        @NotNull(message = "credibility 不能为空")
        @Min(value = 0, message = "可信度不能小于0")
        @Max(value = 100, message = "可信度不能大于100")
        private Integer credibility = 50;

        @NotNull(message = "enabled 不能为空")
        private Boolean enabled = true;
    }

    @Data
    public static class SubscriptionRequest {
        @NotNull(message = "sourceKeys 不能为空")
        @Size(max = 50, message = "来源订阅不能超过50个")
        private List<@NotBlank(message = "来源 key 不能为空") String> sourceKeys = List.of();

        @NotNull(message = "topics 不能为空")
        @Size(max = 50, message = "主题订阅不能超过50个")
        private List<@NotBlank(message = "主题不能为空") @Size(max = 80, message = "主题不能超过80个字符") String> topics = List.of();
    }
}
