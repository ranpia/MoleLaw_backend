package com.MoleLaw_backend.law.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "app.law-vector")
public class LawVectorProperties {
    private boolean enabled;
    private String embeddingBaseUrl = "http://localhost:11436";
    private String model = "kure-v1:official";
    private String revision = "8b418a58414668e75532ed045c22d9ca018ae2b2";
    private int dimensions = 1024;
    private int threads = 6;
    private int batchSize = 16;
    private int embeddingMaxCalls = 1024;
    private int indexingMaxCalls = 1024;
    private String qdrantBaseUrl = "http://localhost:6333";
    private String collectionName = "molelaw_service_law_kure_v1";
    private String apiKey = "";
    private int chunkSize = 300;
    private int chunkOverlap = 64;
    private int embeddingConcurrency = 1;
    private int embeddingQueueCapacity = 4;
    private long embeddingWaitMillis = 5000;
    private long embeddingTimeoutSeconds = 900;
    private int embeddingReadTimeoutMillis = 120_000;
}
