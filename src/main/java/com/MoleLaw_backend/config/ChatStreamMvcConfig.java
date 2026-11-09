package com.MoleLaw_backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
public class ChatStreamMvcConfig {
    @Bean
    public ThreadPoolTaskExecutor chatStreamMvcExecutor(
            @Value("${app.chat-stream.write-threads:4}") int threads) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("chat-sse-write-");
        return executor;
    }

}
