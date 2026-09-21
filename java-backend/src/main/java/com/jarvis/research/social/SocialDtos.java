package com.jarvis.research.social;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

public final class SocialDtos {
    private SocialDtos() { }

    @Data
    public static class ProfileUpdateRequest {
        @NotBlank @Size(max = 60)
        private String displayName;
        @Size(max = 500)
        private String avatarUrl;
        @Size(max = 160)
        private String signature;
        @Size(max = 200)
        private String contactInfo;
        private boolean profilePublic = true;
        private boolean contactPublic = false;
        private boolean activityPublic = true;
    }

    @Data
    public static class GroupRequest {
        @NotBlank @Size(max = 80)
        private String name;
        @Size(max = 500)
        private String description;
        @Pattern(regexp = "OPEN|CLOSED")
        private String visibility = "OPEN";
    }

    @Data
    public static class PostRequest {
        @NotBlank @Size(max = 4000)
        private String content;
        @Size(max = 32)
        private String referenceType;
        @Size(max = 120)
        private String referenceId;
    }

    @Data
    public static class MessageRequest {
        @NotBlank @Size(max = 2000)
        private String content;
    }
}
