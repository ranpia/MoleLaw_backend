package com.MoleLaw_backend.law.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.law-candidate-selection")
public class LawCandidateSelectionProperties {
    @Min(1)
    @Max(100)
    private int minimumScore = 70;

    @Min(1)
    @Max(6)
    private int maxCandidates = 2;
}
