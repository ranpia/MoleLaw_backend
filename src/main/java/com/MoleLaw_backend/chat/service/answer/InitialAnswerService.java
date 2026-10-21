package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.LegalEvidenceCollector;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/** Coordinates collection, generation and validation before the initial persistence transaction. */
@Service
public class InitialAnswerService {
    private final LegalEvidenceCollector collector;
    private final ChatClient answers;
    private final EvidencePromptBuilder prompts;
    private final CitationValidator citations;
    private final EvidenceRenderer renderer;

    public InitialAnswerService(LegalEvidenceCollector collector,
                               @Qualifier("legalAnswerChatClient") ChatClient answers,
                               EvidencePromptBuilder prompts, CitationValidator citations, EvidenceRenderer renderer) {
        this.collector = collector;
        this.answers = answers;
        this.prompts = prompts;
        this.citations = citations;
        this.renderer = renderer;
    }

    public PreparedLegalAnswer prepareAnswer(String query) {
        var evidence = collector.collect(query);
        try {
            String answer = answers.prompt().messages(List.of(new UserMessage(prompts.build(query, evidence))))
                    .call().content();
            citations.validate(answer, evidence);
            return new PreparedLegalAnswer(new GptAnswerResponse(answer, renderer.render(evidence)), evidence);
        } catch (GptApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    public GptAnswerResponse getAnswer(String query) {
        return prepareAnswer(query).response();
    }
}
