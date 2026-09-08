package com.jarvis.research;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 贾维斯金融投研平台 - 主后端入口
 * Spring Boot 3 + Java 17
 */
@SpringBootApplication
@EnableScheduling
@EnableRetry
public class ResearchBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResearchBackendApplication.class, args);
    }
}
