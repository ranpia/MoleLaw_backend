package com.MoleLaw_backend.law.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

@Getter @Setter @Component @Validated
@ConfigurationProperties("app.case-search")
public class CaseSearchProperties {
    @Min(8) @Max(100) private int maxApiCalls = 64;
    @Min(100) @Max(20000) private int maxCandidates = 5000;
    @Min(1) @Max(10) private int maxDetails = 6;
    @Min(1) @Max(6) private int detailsPerSearch = 4;
    @Min(1) @Max(2) private int maxSelected = 2;
    @Min(1) @Max(100) private int minimumScore = 60;
    @Min(500) @Max(8000) private int excerptChars = 4000;
    @Min(1) @Max(3) private int maxRerankCalls = 2;
}
