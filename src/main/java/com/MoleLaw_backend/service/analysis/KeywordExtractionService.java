package com.MoleLaw_backend.service.analysis;

import com.MoleLaw_backend.dto.response.KeywordExtractionResponse;
import com.MoleLaw_backend.util.exception.ErrorCode;
import com.MoleLaw_backend.util.exception.GptApiException;
import com.MoleLaw_backend.service.ai.AiChatService;
import com.MoleLaw_backend.service.ai.AiPrompts;
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
