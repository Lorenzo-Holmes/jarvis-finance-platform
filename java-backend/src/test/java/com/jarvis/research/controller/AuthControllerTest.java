package com.jarvis.research.controller;

import com.jarvis.research.audit.AuditService;
import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.security.AuthService;
import com.jarvis.research.security.GitHubOAuthService;
import com.jarvis.research.service.AuthRateLimitService;
import com.jarvis.research.service.EmailVerificationService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthControllerTest {

    @Test
    void csrfEndpointSeedsLongLivedHttpOnlyDeviceCookieOnce() {
        JarvisProperties props = new JarvisProperties();
        props.getAuth().setDeviceCookieName("jarvis_device");
        props.getAuth().setSameSite("Strict");
        AuthController controller = new AuthController(
                mock(AuthService.class), props, mock(AuthRateLimitService.class),
                mock(AuditService.class), mock(EmailVerificationService.class),
                mock(GitHubOAuthService.class));
        CsrfToken csrf = mock(CsrfToken.class);
        when(csrf.getToken()).thenReturn("csrf-token");
        when(csrf.getHeaderName()).thenReturn("X-XSRF-TOKEN");

        MockHttpServletRequest firstRequest = new MockHttpServletRequest();
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        controller.csrf(csrf, firstRequest, firstResponse);

        String setCookie = firstResponse.getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.startsWith("jarvis_device="));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Strict"));
        assertTrue(setCookie.contains("Max-Age=31536000"));

        String value = setCookie.substring("jarvis_device=".length(), setCookie.indexOf(';'));
        MockHttpServletRequest secondRequest = new MockHttpServletRequest();
        secondRequest.setCookies(new Cookie("jarvis_device", value));
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();
        controller.csrf(csrf, secondRequest, secondResponse);

        assertNull(secondResponse.getHeader("Set-Cookie"),
                "已有合法设备标识时不应每次刷新 CSRF 都重发长期 Cookie");
    }
}
