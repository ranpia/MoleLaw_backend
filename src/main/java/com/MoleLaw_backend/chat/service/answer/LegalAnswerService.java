package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.LegalEvidenceCollector;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/** Answer workflow only; collection and snapshot reads precede generation, persistence follows validation. */
@Service
public class LegalAnswerService {
    private final LegalEvidenceCollector collector;
    private final ChatClient initialAnswers;
    private final ChatClient followUpAnswers;
    private final ChatEvidenceService evidence;
    private final EvidencePromptBuilder prompts;
    private final CitationValidator citations;
    private final EvidenceRenderer renderer;

    public LegalAnswerService(LegalEvidenceCollector collector,
                              @Qualifier("legalAnswerChatClient") ChatClient initialAnswers,
                              @Qualifier("followUpAnswerChatClient") ChatClient followUpAnswers,
                              ChatEvidenceService evidence, EvidencePromptBuilder prompts,
                              CitationValidator citations, EvidenceRenderer renderer) {
        this.collector = collector;
        this.initialAnswers = initialAnswers;
        this.followUpAnswers = followUpAnswers;
        this.evidence = evidence;
        this.prompts = prompts;
        this.citations = citations;
        this.renderer = renderer;
    }

    public PreparedLegalAnswer prepareInitialAnswer(String question) {
        var sources = collector.collect(question);
        String answer = generate(initialAnswers, question, sources, null);
        try {
            return new PreparedLegalAnswer(new GptAnswerResponse(answer, renderer.render(sources)), sources);
        } catch (RuntimeException failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    public GptAnswerResponse getAnswer(String question) {
        return prepareInitialAnswer(question).response();
    }

    /** IDs come from authenticated server state after room ownership checks. */
    public GptAnswerResponse answerFollowUp(Long userId, Long roomId, String question) {
        if (userId == null || userId < 1 || roomId == null || roomId < 1 || question == null || question.isBlank()) {
            throw new IllegalArgumentException("Invalid authorized conversation");
        }
        var sources = evidence.load(userId, roomId);
        return new GptAnswerResponse(generate(followUpAnswers, question, sources, userId + ":" + roomId), "");
    }

    private String generate(ChatClient client, String question, List<EvidenceItem> sources, String conversationId) {
        try {
            var request = client.prompt().messages(List.of(new UserMessage(prompts.build(question, sources))));
            if (conversationId != null) {
                request.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
            }
            return citations.validate(request.call().content(), sources);
        } catch (GptApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
