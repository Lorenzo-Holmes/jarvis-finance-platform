package com.jarvis.research.service;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 认证入口轻量限流。
 * 单实例场景使用内存窗口；未来多实例部署时替换为 Redis 即可，Controller 无需变化。
 */
@Service
public class AuthRateLimitService {

    private static final int LOGIN_ACCOUNT_LIMIT = 10;
    private static final int LOGIN_IP_LIMIT = 30;
    private static final int LOGIN_DEVICE_LIMIT = 20;
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final int REGISTER_IP_LIMIT = 5;
    private static final int REGISTER_DEVICE_LIMIT = 3;
    private static final Duration REGISTER_WINDOW = Duration.ofHours(1);
    private static final int EMAIL_CODE_EMAIL_LIMIT = 5;
    private static final int EMAIL_CODE_IP_LIMIT = 20;
    private static final int EMAIL_CODE_DEVICE_LIMIT = 10;
    private static final Duration EMAIL_CODE_WINDOW = Duration.ofHours(1);
    private static final int MAX_TRACKED_KEYS_PER_WINDOW = 50_000;

    private final Map<String, Window> loginAccountWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginIpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> loginDeviceWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> registerIpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> registerDeviceWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> emailCodeAccountWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> emailCodeIpWindows = new ConcurrentHashMap<>();
    private final Map<String, Window> emailCodeDeviceWindows = new ConcurrentHashMap<>();
    private final AtomicLong cleanupTicker = new AtomicLong();

    public void checkLogin(String clientIp, String email) {
        String normalizedEmail = email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
        consume(loginAccountWindows, normalizedEmail,
                LOGIN_ACCOUNT_LIMIT, LOGIN_WINDOW, "该账号登录尝试过于频繁，请稍后再试");
        consume(loginIpWindows, safe(clientIp),
                LOGIN_IP_LIMIT, LOGIN_WINDOW, "登录请求过于频繁，请稍后再试");
    }

    public void checkLogin(String clientIp, String email, String deviceId) {
        checkLogin(clientIp, email);
        consume(loginDeviceWindows, safe(deviceId),
                LOGIN_DEVICE_LIMIT, LOGIN_WINDOW, "该设备登录请求过于频繁，请稍后再试");
    }

    public void checkRegister(String clientIp) {
        consume(registerIpWindows, safe(clientIp),
                REGISTER_IP_LIMIT, REGISTER_WINDOW, "注册请求过于频繁，请稍后再试");
    }

    public void checkRegister(String clientIp, String deviceId) {
        checkRegister(clientIp);
        consume(registerDeviceWindows, safe(deviceId),
                REGISTER_DEVICE_LIMIT, REGISTER_WINDOW, "该设备注册请求过于频繁，请稍后再试");
    }

    public void checkEmailCodeSend(String clientIp, String email) {
        String normalizedEmail = safe(email).toLowerCase(Locale.ROOT);
        consume(emailCodeAccountWindows, normalizedEmail,
                EMAIL_CODE_EMAIL_LIMIT, EMAIL_CODE_WINDOW, "该邮箱验证码发送过于频繁，请稍后再试");
        consume(emailCodeIpWindows, safe(clientIp),
                EMAIL_CODE_IP_LIMIT, EMAIL_CODE_WINDOW, "该网络验证码发送请求过于频繁，请稍后再试");
    }

    public void checkEmailCodeSend(String clientIp, String email, String deviceId) {
        checkEmailCodeSend(clientIp, email);
        consume(emailCodeDeviceWindows, safe(deviceId),
                EMAIL_CODE_DEVICE_LIMIT, EMAIL_CODE_WINDOW, "该设备验证码发送过于频繁，请稍后再试");
    }

    private void consume(Map<String, Window> windows, String key, int limit,
                         Duration duration, String message) {
        Instant now = Instant.now();
        cleanupExpired(windows, duration, now);
        if (!windows.containsKey(key) && windows.size() >= MAX_TRACKED_KEYS_PER_WINDOW) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "请求来源过多，请稍后再试");
        }
        windows.compute(key, (k, current) -> {
            Window window = current;
            if (window == null || !now.isBefore(window.startedAt.plus(duration))) {
                window = new Window(now, 0);
            }
            if (window.count >= limit) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
            }
            return new Window(window.startedAt, window.count + 1);
        });
    }

    private void cleanupExpired(Map<String, Window> windows, Duration duration, Instant now) {
        // 小表无需每次全量扫描；达到容量上限时必须先清理，普通流量下每 256 个 key 左右顺手回收。
        int size = windows.size();
        if (size < 256) return;
        long tick = cleanupTicker.incrementAndGet();
        if (size < MAX_TRACKED_KEYS_PER_WINDOW && (tick & 255L) != 0L) return;
        Instant threshold = now.minus(duration);
        windows.entrySet().removeIf(entry -> !entry.getValue().startedAt.isAfter(threshold));
    }

    int trackedEmailAccountKeys() { return emailCodeAccountWindows.size(); }
    int trackedEmailIpKeys() { return emailCodeIpWindows.size(); }

    private String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }

    private record Window(Instant startedAt, int count) {}
}
