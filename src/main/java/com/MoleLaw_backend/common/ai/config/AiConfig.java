package com.MoleLaw_backend.common.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Scope;

@Configuration(proxyBeanMethods = false)
public class AiConfig {
    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    public ChatClient.Builder baseAiChatClientBuilder(ChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }

    /** Compatibility client for existing callers until their role-specific migration. */
    @Bean
    @Primary
    public ChatClient chatClient(@Qualifier("baseAiChatClientBuilder") ChatClient.Builder builder) {
        return builder.clone().build();
    }

    @Bean
    public RestClientCustomizer modelHttpTimeouts(
            @Value("${app.ai.connect-timeout-ms:5000}") int connectTimeout,
            @Value("${app.ai.read-timeout-ms:30000}") int readTimeout) {
        return builder -> {
            SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
            factory.setConnectTimeout(connectTimeout);
            factory.setReadTimeout(readTimeout);
            builder.requestFactory(factory);
        };
    }
}
