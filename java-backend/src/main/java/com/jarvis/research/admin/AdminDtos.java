package com.jarvis.research.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;

public final class AdminDtos {

    private AdminDtos() {}

    @Data
    public static class StatusRequest {
        @NotNull(message = "enabled 不能为空")
        private Boolean enabled;
    }

    @Data
    public static class RoleRequest {
        @NotBlank(message = "role 不能为空")
        @Pattern(regexp = "USER|ADMIN", message = "role 只能为 USER 或 ADMIN")
        private String role;
    }

    @Data
    public static class QuotaRequest {
        @NotNull(message = "dailyRequestLimit 不能为空")
        @Min(value = 0, message = "dailyRequestLimit 不能小于0")
        @Max(value = 1_000_000, message = "dailyRequestLimit 过大")
        private Integer dailyRequestLimit;

        @NotNull(message = "monthlyTokenLimit 不能为空")
        @Min(value = 0, message = "monthlyTokenLimit 不能小于0")
        @Max(value = 1_000_000_000L, message = "monthlyTokenLimit 过大")
        private Long monthlyTokenLimit;

        @NotBlank(message = "reason 不能为空")
        private String reason;
    }

    @Data
    public static class PermissionsRequest {
        @NotNull(message = "features 不能为空")
        private List<@NotBlank(message = "功能权限不能为空") String> features;

        @NotBlank(message = "reason 不能为空")
        private String reason;
    }
}
