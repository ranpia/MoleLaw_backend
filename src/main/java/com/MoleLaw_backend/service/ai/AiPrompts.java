package com.MoleLaw_backend.service.ai;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Getter
@Component
public class AiPrompts {
    private final String legalSystem;
    private final String keywordExtraction;

    public AiPrompts(@Value("classpath:prompts/legal-system.txt") Resource legalSystem,
                     @Value("classpath:prompts/keyword-extraction.txt") Resource keywordExtraction) throws IOException {
        this.legalSystem = legalSystem.getContentAsString(StandardCharsets.UTF_8);
        this.keywordExtraction = keywordExtraction.getContentAsString(StandardCharsets.UTF_8);
    }
}
