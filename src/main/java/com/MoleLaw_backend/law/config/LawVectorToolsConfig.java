package com.MoleLaw_backend.law.config;

import com.MoleLaw_backend.law.client.vector.LawVectorClient;
import com.MoleLaw_backend.law.service.tool.LawVectorizationToolService;
import com.MoleLaw_backend.law.service.tool.LawRagSearchToolService;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.MoleLaw_backend.law.service.source.LegalSourceIndexService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(LawVectorProperties.class)
@ConditionalOnProperty(prefix = "app.law-vector", name = "enabled", havingValue = "true")
public class LawVectorToolsConfig {
    @Bean
    public LawVectorClient lawVectorClient(LawVectorProperties properties, ObjectMapper mapper) {
        return new LawVectorClient(properties, mapper);
    }

    @Bean
    public LawVectorizationToolService lawVectorizationToolService(LawVectorClient client,
            LawVectorProperties properties, ObjectMapper mapper, LegalSourceService sources, LegalSourceIndexService indexes) {
        return new LawVectorizationToolService(client, properties, mapper, sources, indexes);
    }

    @Bean
    public LawRagSearchToolService lawRagSearchToolService(LawVectorClient client, LegalSourceIndexService indexes) {
        return new LawRagSearchToolService(client, indexes);
    }
}
