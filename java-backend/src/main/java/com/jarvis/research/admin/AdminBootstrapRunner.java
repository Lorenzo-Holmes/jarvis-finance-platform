package com.jarvis.research.admin;

import com.jarvis.research.config.JarvisProperties;
import com.jarvis.research.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** 通过环境变量完成一次性管理员初始化，不把管理员邮箱写死在代码中。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminBootstrapRunner implements CommandLineRunner {

    private final JarvisProperties properties;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void run(String... args) {
        String email = properties.getAuth().getBootstrapAdminEmail();
        if (email == null || email.isBlank()) return;
        String normalized = email.trim().toLowerCase();
        userRepository.findByEmail(normalized).ifPresentOrElse(user -> {
            if (!"ADMIN".equalsIgnoreCase(user.getRole())) {
                user.setRole("ADMIN");
                userRepository.save(user);
                log.info("已将 {} 提升为 ADMIN；建议完成初始化后清空 BOOTSTRAP_ADMIN_EMAIL", normalized);
            }
        }, () -> log.warn("BOOTSTRAP_ADMIN_EMAIL 对应用户不存在: {}；请先注册该邮箱", normalized));
    }
}
