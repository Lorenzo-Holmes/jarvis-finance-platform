package com.jarvis.research.market;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** 多市场标的偏好接口 DTO。 */
public final class MarketPreferenceDtos {

    private MarketPreferenceDtos() {
    }

    @Data
    @NoArgsConstructor
    public static class UpdateRequest {
        @NotNull(message = "自选标的不能为空")
        @Size(max = 100, message = "自选标的不能超过100个")
        private List<@Valid Instrument> watchlist = new ArrayList<>();

        @NotNull(message = "隐藏默认标的不能为空")
        @Size(max = 100, message = "隐藏默认标的不能超过100个")
        private List<@NotBlank(message = "隐藏默认标的不能为空")
                @Pattern(regexp = "(a_share|us_stock|crypto):[A-Za-z0-9._-]{1,20}",
                        message = "隐藏默认标的格式不正确") String> hiddenDefaultKeys = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    public static class Instrument {
        @NotBlank(message = "市场不能为空")
        @Pattern(regexp = "a_share|us_stock|crypto", message = "市场不受支持")
        private String market;

        @NotBlank(message = "标的代码不能为空")
        @Pattern(regexp = "[A-Za-z0-9._-]{1,20}", message = "标的代码格式不正确")
        private String symbol;

        @Size(max = 80, message = "标的名称不能超过80字符")
        private String name;

        @Size(max = 12, message = "币种不能超过12字符")
        private String currency;

        @Size(max = 40, message = "数据源名称不能超过40字符")
        private String source;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PreferenceView {
        private List<Instrument> watchlist;
        private List<String> hiddenDefaultKeys;
        /** false 表示数据库中尚无记录，前端可将旧 localStorage 数据迁移一次。 */
        private boolean persisted;
    }
}
