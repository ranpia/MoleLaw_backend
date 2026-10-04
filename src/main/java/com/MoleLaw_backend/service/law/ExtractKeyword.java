package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.exception.ErrorCode;
import com.MoleLaw_backend.exception.GptApiException;
import com.MoleLaw_backend.service.ai.AiChatService;
import com.MoleLaw_backend.service.ai.AiPrompts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ExtractKeyword {
    private final AiChatService aiChatService;
    private final AiPrompts prompts;

    public KeywordAndTitleResponse extractKeywords(String userInput) {
        KeywordAndTitleResponse result = aiChatService.generateEntity("당신은 법률 키워드 추출 도우미입니다.",
                prompts.getKeywordExtraction() + "\n사용자 문장:\n" + userInput,
                KeywordAndTitleResponse.class, 0.3);
        if (result.getKeywords() == null || result.getKeywords().isEmpty()
                || result.getKeywords().stream().anyMatch(keyword -> keyword == null || keyword.isBlank())
                || result.getSummary() == null || result.getSummary().isBlank()
                || result.getMinistry() == null || result.getMinistry().isBlank()) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE, "키워드 응답 필수 항목 누락");
        }
        return result;
    }
}
