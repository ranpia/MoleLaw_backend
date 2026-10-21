package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import org.springframework.ai.chat.messages.UserMessage;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class FollowUpAnswerService {
    private final ChatClient answers;
    private final ChatEvidenceService evidence;
    private final CitationValidator citations;
    private final EvidencePromptBuilder prompts;

    public FollowUpAnswerService(@Qualifier("followUpAnswerChatClient") ChatClient answers, ChatEvidenceService evidence,
                                 CitationValidator citations, EvidencePromptBuilder prompts) {
        this.answers = answers;
        this.evidence = evidence;
        this.citations = citations;
        this.prompts = prompts;
    }

    /** IDs come from authenticated server state after room ownership checks. */
    public GptAnswerResponse generateAnswerWithContext(Long userId, Long roomId, String question) {
        if (userId == null || userId < 1 || roomId == null || roomId < 1 || question == null || question.isBlank()) {
            throw new IllegalArgumentException("Invalid authorized conversation");
        }
        var snapshot = evidence.load(userId, roomId);
        try {
            String answer = answers.prompt().messages(List.of(new UserMessage(prompts.build(question, snapshot))))
                    .advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, userId + ":" + roomId))
                    .call().content();
            if (answer == null || answer.isBlank()) throw new GptApiException(ErrorCode.GPT_EMPTY_RESPONSE);
            citations.validate(answer, snapshot);
            return new GptAnswerResponse(answer, "");
        } catch (GptApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
