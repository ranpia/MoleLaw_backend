package com.MoleLaw_backend.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.qdrant.QdrantVectorStore;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@Profile("qdrant & !test")
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {
    @Bean(destroyMethod = "close")
    public QdrantClient qdrantClient(QdrantProperties properties) {
        return new QdrantClient(QdrantGrpcClient.newBuilder(
                properties.host(), properties.port(), properties.useTls())
                .withApiKey(properties.apiKey()).withTimeout(Duration.ofSeconds(5)).build());
    }

    @Bean
    public QdrantVectorStore qdrantVectorStore(QdrantClient client, EmbeddingModel embeddingModel,
                                              QdrantProperties properties) {
        return QdrantVectorStore.builder(client, embeddingModel)
                .collectionName(properties.collectionName())
                .initializeSchema(false)
                .build();
    }
}
