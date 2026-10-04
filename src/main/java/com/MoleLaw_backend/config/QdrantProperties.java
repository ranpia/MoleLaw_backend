package com.MoleLaw_backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.qdrant")
public record QdrantProperties(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotBlank String apiKey,
        @NotBlank String collectionName,
        boolean useTls) {
}
