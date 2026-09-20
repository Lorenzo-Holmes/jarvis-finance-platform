package com.jarvis.research.agent;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Agent 专用受控执行器，避免每个请求创建裸线程。 */
@Configuration
public class AgentRuntimeConfig {

    @Bean(name = "agentTaskExecutor", destroyMethod = "shutdown")
    public ThreadPoolTaskExecutor agentTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(16);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("jarvis-agent-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}
