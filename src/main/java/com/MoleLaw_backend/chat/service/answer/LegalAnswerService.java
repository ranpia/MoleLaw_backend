package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.answer.PreparedLegalAnswer;
import com.MoleLaw_backend.chat.dto.answer.PreparedFollowUpAnswer;

import com.MoleLaw_backend.chat.dto.response.LegalAnswerResponse;
import com.MoleLaw_backend.chat.service.ChatContentLimits;
import com.MoleLaw_backend.chat.service.evidence.ChatEvidenceService;
import com.MoleLaw_backend.chat.service.evidence.CitationValidator;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.service.retrieval.LegalEvidenceCollector;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;

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
    private final FollowUpCaseToolsFactory caseTools;

    public LegalAnswerService(LegalEvidenceCollector collector,
                              @Qualifier("legalAnswerChatClient") ChatClient initialAnswers,
                              @Qualifier("followUpAnswerChatClient") ChatClient followUpAnswers,
                              ChatEvidenceService evidence, EvidencePromptBuilder prompts,
                              CitationValidator citations, EvidenceRenderer renderer, FollowUpCaseToolsFactory caseTools) {
        this.collector = collector;
        this.initialAnswers = initialAnswers;
        this.followUpAnswers = followUpAnswers;
        this.evidence = evidence;
        this.prompts = prompts;
        this.citations = citations;
        this.renderer = renderer;
        this.caseTools = caseTools;
    }

    public PreparedLegalAnswer prepareInitialAnswer(String question) {
        ChatContentLimits.question(question);
        var collected = collector.collectForAnswer(question);
        var sources = collected.evidence();
        String answer = generate(initialAnswers, question, sources, null, collected.plan());
        try {
            return new PreparedLegalAnswer(new LegalAnswerResponse(answer, renderer.render(sources)), sources);
        } catch (RuntimeException failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }

    public LegalAnswerResponse getAnswer(String question) {
        return prepareInitialAnswer(question).response();
    }

    /** IDs come from authenticated server state after room ownership checks. */
    public LegalAnswerResponse answerFollowUp(Long userId, Long roomId, String question) {
        return prepareFollowUp(userId, roomId, question).response();
    }

    public PreparedFollowUpAnswer prepareFollowUp(Long userId, Long roomId, String question) {
        return prepareFollowUp(userId, roomId, question, false);
    }

    public PreparedFollowUpAnswer prepareFollowUp(Long userId, Long roomId, String question, boolean allowCaseSearch) {
        if (userId == null || userId < 1 || roomId == null || roomId < 1 || question == null || question.isBlank()) {
            throw new IllegalArgumentException("Invalid authorized conversation");
        }
        ChatContentLimits.question(question);
        var sources = evidence.load(userId, roomId);
        var tools = caseTools.create(sources, allowCaseSearch);
        String answer = generate(followUpAnswers, question, sources, userId + ":" + roomId, null, tools);
        ChatContentLimits.answer(answer);
        return new PreparedFollowUpAnswer(new LegalAnswerResponse(answer, ""),
                tools.citedAdditions(answer), tools.consentRequested(), allowCaseSearch);
    }

    private String generate(ChatClient client, String question, List<EvidenceItem> sources, String conversationId,
            QuestionRetrievalPlan plan) {
        return generate(client, question, sources, conversationId, plan, null);
    }

    private String generate(ChatClient client, String question, List<EvidenceItem> sources, String conversationId,
            QuestionRetrievalPlan plan, FollowUpCaseToolsFactory.Session tools) {
        try {
            String input = tools == null ? prompts.build(question, sources, plan) : prompts.buildFollowUp(question, sources);
            var request = client.prompt().messages(List.of(new UserMessage(input)));
            if (conversationId != null) {
                request.advisors(advisor -> advisor.param(ChatMemory.CONVERSATION_ID, conversationId));
            }
            if (tools != null) {
                var callbacks = java.util.Arrays.stream(org.springframework.ai.support.ToolCallbacks.from(tools))
                        .filter(callback -> tools.consentGranted()
                                ? !callback.getToolDefinition().name().equals("requestCaseSearchConsent")
                                : !callback.getToolDefinition().name().equals("searchRoomCases")).toList();
                request.toolCallbacks(callbacks);
            }
            String answer = request.call().content();
            if (tools != null && tools.consentRequested()) answer = tools.consentMessage();
            ChatContentLimits.answer(answer);
            return citations.validate(answer, tools == null ? sources : tools.evidence());
        } catch (GptApiException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
