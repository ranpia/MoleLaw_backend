package com.MoleLaw_backend.law.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.law-reranker")
public class LawRerankerProperties {
    private boolean enabled;
    @NotBlank
    private String baseUrl = "http://localhost:11437";
    @NotBlank
    private String model = "BAAI/bge-reranker-v2-m3";
    @NotBlank
    private String revision = "953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e";
    @Min(15)
    @Max(100)
    private int candidateLimit = 50;
    @Min(100)
    @Max(120000)
    private int readTimeoutMillis = 30000;
}
