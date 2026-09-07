package com.jarvis.research.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 平台配置: 黄金行情 + Python 微服务 + AI 多协议
 */
@Component
@ConfigurationProperties(prefix = "jarvis")
@Data
public class JarvisProperties {

    private Gold gold = new Gold();
    private PythonService pythonService = new PythonService();
    private Cors cors = new Cors();
    private Auth auth = new Auth();
    private OAuth oauth = new OAuth();
    private Email email = new Email();

    @Data
    public static class Gold {
        private String realtimeUrl = "https://qt.gtimg.cn/q={symbol}";
        private String klineUrl = "https://web.ifzq.gtimg.cn/appstock/app/fqkline/get";
        private String defaultSymbol = "sh518850";
    }

    @Data
    public static class PythonService {
        private boolean enabled = true;
        private String baseUrl = "http://127.0.0.1:8100";
        /** Java -> Python 内部调用凭证，仅通过环境变量注入。 */
        private String internalToken = "";
    }

    @Data
    public static class Auth {
        private String cookieName = "jarvis_token";
        private String cookieDomain = "";
        private boolean cookieSecure = false;
        private String sameSite = "Lax";
        /** 可选：启动时将已存在的指定邮箱提升为 ADMIN，不在代码或数据库内写死管理员。 */
        private String bootstrapAdminEmail = "";
        /** 邮箱注册是否必须先完成一次性验证码校验。生产环境建议保持 true。 */
        private boolean requireEmailVerification = true;
    }

    @Data
    public static class OAuth {
        private boolean enabled = false;
        private String clientId = "";
        private String clientSecret = "";
        private String redirectUri = "http://localhost:8200/api/auth/github/callback";
        private String frontendRedirectUri = "http://localhost:5173";
        private String authorizeUrl = "https://github.com/login/oauth/authorize";
        private String tokenUrl = "https://github.com/login/oauth/access_token";
        private String userUrl = "https://api.github.com/user";
        private String emailsUrl = "https://api.github.com/user/emails";
        private int stateTtlSeconds = 600;
    }

    @Data
    public static class Email {
        private String provider = "resend";
        private String apiKey = "";
        private String from = "JARVIS 投研 <noreply@example.com>";
        private String apiUrl = "https://api.resend.com/emails";
        private int codeTtlSeconds = 600;
        private int maxVerifyAttempts = 5;
    }

    @Data
    public static class Cors {
        private List<String> allowedOrigins = List.of(
                "https://f.shengxia.me",
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        );
    }
}
