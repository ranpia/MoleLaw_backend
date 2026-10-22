package com.MoleLaw_backend.law.service.analysis;

import com.MoleLaw_backend.law.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.ai.AiChatService;
import com.MoleLaw_backend.common.ai.AiPrompts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class KeywordExtractionService {
    private final AiChatService aiChatService;
    private final AiPrompts prompts;

    public KeywordExtractionResponse extractKeywords(String userInput) {
        KeywordExtractionResponse result = aiChatService.generateEntity("당신은 법률 키워드 추출 도우미입니다.",
                prompts.getKeywordExtraction() + "\n사용자 문장:\n" + userInput,
                KeywordExtractionResponse.class, 0.3);
        if (result.getKeywords() == null || result.getKeywords().isEmpty()
                || result.getKeywords().stream().anyMatch(keyword -> keyword == null || keyword.isBlank())
                || result.getSummary() == null || result.getSummary().isBlank()
                || result.getMinistry() == null || result.getMinistry().isBlank()) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, "키워드 응답 필수 항목 누락");
        }
        return result;
    }
}
