package com.MoleLaw_backend.config;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class ChatStreamAsyncConfigurer implements WebMvcConfigurer {
    private final ThreadPoolTaskExecutor executor;
    private final Duration timeout;

    public ChatStreamAsyncConfigurer(@Qualifier("chatStreamMvcExecutor") ThreadPoolTaskExecutor executor,
            @Value("${app.chat-stream.timeout:20m}") Duration timeout) {
        this.executor = executor;
        this.timeout = timeout;
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(timeout.plusSeconds(30).toMillis());
        configurer.setTaskExecutor(executor);
    }
}
