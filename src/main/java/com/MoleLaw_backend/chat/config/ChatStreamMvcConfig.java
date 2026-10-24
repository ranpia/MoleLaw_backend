package com.MoleLaw_backend.chat.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import java.time.Duration;

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

    @Bean
    public WebMvcConfigurer chatStreamAsyncConfigurer(ThreadPoolTaskExecutor chatStreamMvcExecutor,
            @Value("${app.chat-stream.timeout:20m}") Duration timeout) {
        return new WebMvcConfigurer() {
            @Override public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
                configurer.setDefaultTimeout(timeout.plusSeconds(30).toMillis());
                configurer.setTaskExecutor(chatStreamMvcExecutor);
            }
        };
    }
}
