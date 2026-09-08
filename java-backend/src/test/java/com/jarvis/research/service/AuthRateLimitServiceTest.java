package com.jarvis.research.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthRateLimitServiceTest {

    @Test
    void loginAccountIsLimitedIndependentlyOfIp() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.checkLogin("10.0.0." + i, "User@Example.com");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkLogin("10.0.0.99", "user@example.com"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void registrationIsLimitedPerIp() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 5; i++) service.checkRegister("1.2.3.4");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkRegister("1.2.3.4"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void emailCodeIsLimitedPerEmailAcrossRotatingIps() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 5; i++) {
            service.checkEmailCodeSend("10.10.0." + i, "User@Example.com");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkEmailCodeSend("10.10.0.99", "user@example.com"));
        assertEquals(429, ex.getStatusCode().value());
        assertEquals(1, service.trackedEmailAccountKeys());
    }

    @Test
    void emailCodeIsLimitedPerIpAcrossRotatingEmails() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 20; i++) {
            service.checkEmailCodeSend("9.9.9.9", "user" + i + "@example.com");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkEmailCodeSend("9.9.9.9", "another@example.com"));
        assertEquals(429, ex.getStatusCode().value());
        assertEquals(1, service.trackedEmailIpKeys());
    }

    @Test
    void registrationIsLimitedPerDeviceAcrossRotatingIps() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 3; i++) {
            service.checkRegister("172.16.0." + i, "device-alpha");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkRegister("172.16.0.99", "device-alpha"));
        assertEquals(429, ex.getStatusCode().value());
    }

    @Test
    void emailCodeIsLimitedPerDeviceAcrossRotatingIpsAndEmails() {
        AuthRateLimitService service = new AuthRateLimitService();
        for (int i = 0; i < 10; i++) {
            service.checkEmailCodeSend("192.0.2." + i, "device-user" + i + "@example.com", "device-beta");
        }
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.checkEmailCodeSend("192.0.2.99", "fresh@example.com", "device-beta"));
        assertEquals(429, ex.getStatusCode().value());
    }
}
