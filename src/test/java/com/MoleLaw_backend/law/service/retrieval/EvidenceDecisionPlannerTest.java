package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class EvidenceDecisionPlannerTest {
    private final EvidenceReadPlanner reading = mock(EvidenceReadPlanner.class);

    @org.junit.jupiter.api.BeforeEach
    void supplyReadEvidence() {
        when(reading.read(anyString(), anyList(), any())).thenAnswer(call ->
                new com.MoleLaw_backend.law.dto.retrieval.EvidenceReading(
                        new ObjectMapper().createArrayNode(), call.getArgument(1), List.of()));
    }
    @Test
    void fieldConstraintDiagnosticReportsOnlyShapeWithoutRetryOrSensitiveValues(CapturedOutput output) {
        respond("""
                {"action":"COLLECT_LAW","reason":"private-reason","missingIssues":["private-issue"],
                "evidenceIds":[],"searchQueries":[],"lawSearches":[
                {"query":"private-law","field":"TITLE","exactName":false,"ministry":"private-ministry"}]}
                """);
        assertThrows(GptApiException.class, () -> planner.decide("private-question", evidence));
        verify(model, times(1)).call(any(Prompt.class));
        assertThat(output.getOut()).contains("field constraint shape=", "invalidMinistryCount", "FIELD_CONSTRAINT")
                .doesNotContain("private-reason", "private-issue", "private-law", "private-ministry", "private-question");
    }
    private final ChatModel model = mock(ChatModel.class);
    private final EvidenceDecisionPlanner planner = new EvidenceDecisionPlanner(ChatClient.builder(model).build(), new ObjectMapper(), 2500, reading, 20000, mock(org.springframework.beans.factory.ObjectProvider.class));
    private final List<EvidenceItem> evidence = List.of(new EvidenceItem("known-id", "LAW_CHUNK", "chunk:1",
            "민법", "https://www.law.go.kr", "확보한 본문"));

    @Test
    void sendsServerBudgetAndIndexBoundaryButPreservesTheModelsCollectionChoice() throws Exception {
        respond("""
                {"action":"COLLECT_LAW","reason":"기존 색인에 관련 법령 없음","missingIssues":["공항 규제"],
                "evidenceIds":[],"searchQueries":[],"lawSearches":[
                {"query":"공항","field":"TITLE","exactName":false,"ministry":null}]}
                """);
        var result = planner.decide("질문", evidence, null, List.of(), 1);
        assertThat(result.action()).isEqualTo(com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision.Action.COLLECT_LAW);
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        var state = new ObjectMapper().readTree(prompt.getValue().getUserMessage().getText()).path("executionState");
        assertThat(state.path("remainingActions").asInt()).isEqualTo(1);
        assertThat(state.path("vectorSearchScope").asText()).isEqualTo("EXISTING_INDEX_ONLY");
    }

    @Test
    void omittedIdsCannotBeApprovedAndTheirBodiesAreNeverSent() throws Exception {
        var planner = new EvidenceDecisionPlanner(ChatClient.builder(model).build(), new ObjectMapper(), 256, reading, 20000, mock(org.springframework.beans.factory.ObjectProvider.class));
        var hidden = new EvidenceItem("hidden-id", "LAW_CHUNK", "chunk:2", "긴 조문",
                "https://www.law.go.kr", "법령 본문 ".repeat(1000));
        when(reading.read(anyString(), anyList(), any())).thenReturn(
                new com.MoleLaw_backend.law.dto.retrieval.EvidenceReading(
                        new ObjectMapper().createArrayNode().add(new ObjectMapper().createObjectNode()
                                .put("id", "hidden-id").put("title", "긴 조문")),
                        List.of(evidence.get(0)), List.of()));
        respond("{\"action\":\"ANSWER_READY\",\"reason\":\"충분함\",\"missingIssues\":[],\"evidenceIds\":[\"hidden-id\"],\"searchQueries\":[],\"lawSearches\":[]}");
        assertThrows(GptApiException.class, () -> planner.decide("질문", List.of(hidden, evidence.get(0))));
        var prompt = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(prompt.capture());
        var input = new ObjectMapper().readTree(prompt.getValue().getUserMessage().getText());
        assertThat(input.path("candidateCatalog").toString()).contains("hidden-id");
        assertThat(input.path("evidence").toString()).doesNotContain("hidden-id", "법령 본문");
    }

    @Test
    void mapsProviderFailureWithoutLeakingItsMessageOrRetrying(CapturedOutput output) {
        when(model.call(any(Prompt.class))).thenThrow(new IllegalStateException("private provider payload"));

        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));

        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("private provider payload");
        verify(model).call(any(Prompt.class));
        assertThat(output.getOut()).contains("stage=MODEL_CALL", "errorType=IllegalStateException")
                .doesNotContain("private provider payload");
    }

    @Test
    void mapsMalformedModelDecisionToSafeFailure(CapturedOutput output) {
        respond("private invalid model output");
        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
        assertThat(failure.getMessage()).doesNotContain("private invalid model output");
        assertThat(output.getOut()).contains("stage=DECISION_VALIDATION")
                .doesNotContain("private invalid model output");
    }

    @Test
    void localDiagnosticsExposeValidatedReasonAndVisibleTitlesWithoutQuestionOrBody(CapturedOutput output) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EvidenceDecisionPlanner.class);
        var previous = logger.getLevel();
        try {
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            respond("""
                    {"action":"INSUFFICIENT","reason":"위치 누락","missingIssues":["실제 높이"],
                    "evidenceIds":[],"searchQueries":[],"lawSearches":[]}
                    """);
            planner.decide("private question", evidence);
            assertThat(output.getOut()).contains("Evidence decision diagnostic=", "위치 누락", "실제 높이",
                            "visibleCount", "민법")
                    .doesNotContain("private question", "확보한 본문");
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    void doesNotAcceptAnAnswerDecisionWithAnUnknownEvidenceId(CapturedOutput output) {
        respond("""
                {"action":"ANSWER_READY","reason":"충분함","missingIssues":[],"evidenceIds":["foreign-id"],
                 "searchQueries":[],"lawSearches":[]}
                """);
        GptApiException failure = assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));
        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.GPT_API_FAILURE);
        assertThat(output.getOut()).contains("validationCode=UNKNOWN_EVIDENCE_ID");
    }

    @Test
    void reportsActionContractFailureWithoutAcceptingAnIncompleteCollection(CapturedOutput output) {
        respond("""
                {"action":"COLLECT_LAW","reason":"private reasoning","missingIssues":["규제"],
                 "evidenceIds":[],"searchQueries":[],"lawSearches":[]}
                """);
        assertThrows(GptApiException.class, () -> planner.decide("질문", evidence));
        assertThat(output.getOut()).contains("validationCode=ACTION_CONTRACT").doesNotContain("private reasoning");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void correctsOnlyTheActionContractOnceAndKeepsTheModelsNewChoice() {
        String invalid = """
                {"action":"COLLECT_LAW","reason":"private initial reasoning","missingIssues":["規制"],
                 "evidenceIds":[],"searchQueries":[],"lawSearches":[]}
                """;
        String corrected = """
                {"action":"INSUFFICIENT","reason":"条件なし","missingIssues":["条件"],
                 "evidenceIds":[],"searchQueries":[],"lawSearches":[]}
                """;
        when(model.call(any(Prompt.class))).thenReturn(
                new ChatResponse(List.of(new Generation(new AssistantMessage(invalid)))),
                new ChatResponse(List.of(new Generation(new AssistantMessage(corrected)))));
        assertThat(planner.decide("질문", evidence).action())
                .isEqualTo(com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision.Action.INSUFFICIENT);
        var prompts = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(1).getContents()).contains("rejectedProposal", "lawSearchesCount")
                .doesNotContain("private initial reasoning");
    }

    private void respond(String text) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text)))));
    }
}
