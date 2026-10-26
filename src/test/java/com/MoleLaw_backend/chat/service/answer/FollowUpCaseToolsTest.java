package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.service.tool.CaseEvidenceReader;
import com.MoleLaw_backend.law.service.tool.LegalApiToolService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FollowUpCaseToolsTest {
    @Test void roomAnswerPlanBlocksConsentAndSearchEvenIfCalledDirectly() {
        var session = factory.create(List.of(law), true);
        session.prepare(new com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan(
                com.MoleLaw_backend.chat.dto.answer.FollowUpCasePlan.Action.ANSWER_FROM_ROOM, null, "", "정리 요청"));
        assertThatThrownBy(() -> session.requestConsent(law.id(), "추가 검색")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.search(law.id(), "검색", 1)).isInstanceOf(IllegalStateException.class);
        assertThat(session.consentRequested()).isFalse();
        verify(pipeline, never()).search(any(), any(), anyString(), anyInt(), any(), any());
    }
    @Test void selectedPrefetchedBodiesAreReadWithoutAnotherApiCall() {
        var item = new EvidenceItem(UUID.randomUUID().toString(), "CASE_BODY", "901", "임금", "https://www.law.go.kr", "검증된 판결 본문");
        var candidate = new LegalToolContracts.Candidate("901", null, "임금", null, "2026다1", "20260101", "대법원");
        when(pipeline.search(any(), any(), anyString(), anyInt(), any(), any())).thenReturn(new com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome(
                "AVAILABLE", List.of(candidate), java.util.Map.of("901", item), List.of(), 1, 1, 1, false, false, false));
        var session = factory.create(List.of(law), true);
        session.search(law.id(), "쟁점", 1);
        assertThat(session.evidence()).containsExactly(law);
        assertThat(session.read("901")).containsEntry("evidence", item);
        assertThat(session.citedAdditions("[[" + item.id() + "]]")).containsExactly(item);
        verifyNoInteractions(api, reader);
    }
    @Test void casePipelineUsesTheParentStreamTraceIdAndDoesNotPublishUnrankedEvidence() {
        var stages = new java.util.ArrayList<String>();
        var session = factory.create(List.of(law), true, "shared-stream-trace", 5L, stages::add);
        when(pipeline.search(any(), any(), anyString(), anyInt(), any(), any())).thenAnswer(call -> {
            com.MoleLaw_backend.law.service.tool.LegalToolContext context = call.getArgument(0);
            assertThat(context.executionId()).isEqualTo("shared-stream-trace");
            com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline.State state = call.getArgument(4);
            assertThat(state.roomId()).isEqualTo(5L);
            java.util.function.Consumer<String> progress = call.getArgument(5);
            progress.accept("CASE_RERANK");
            return new com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome("NO_RELEVANT_CASES", List.of(), java.util.Map.of(), List.of(), 3, 5, 6, false, false, false);
        });
        var result = session.search(law.id(), "쟁점", 1);
        assertThat(result).containsEntry("status", "NO_RELEVANT_CASES").containsEntry("queryRevisionRequired", true);
        assertThat(stages).containsExactly("CASE_RERANK");
        assertThat(session.evidence()).containsExactly(law);
    }
    @Test void defaultConsentGateProposesButNeverSearches() {
        var session = factory.create(List.of(law));
        assertThat(session.requestConsent(law.id(), "판례로 쟁점을 보강")).containsEntry("status", "CONSENT_REQUIRED");
        assertThat(session.consentRequested()).isTrue();
        assertThatThrownBy(() -> session.search(law.id(), "쟁점", 1)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> session.read("901")).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(api, reader);
    }
    private final LegalApiToolService api = mock(LegalApiToolService.class);
    private final CaseEvidenceReader reader = mock(CaseEvidenceReader.class);
    private final com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline pipeline = mock(com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline.class);
    private final FollowUpCaseToolsFactory factory = new FollowUpCaseToolsFactory(api, reader, pipeline);
    private final EvidenceItem law = new EvidenceItem(UUID.randomUUID().toString(), "LAW_CHUNK", "law:1",
            "검증법 제1조(목적)", "https://www.law.go.kr", "검증 조문");

    private void searchResult() {
        var candidate = new LegalToolContracts.Candidate("901", null, "사건", null, "2026다1", "20260101", "검증법원");
        when(pipeline.search(any(), any(), anyString(), anyInt(), any(), any())).thenReturn(
                new com.MoleLaw_backend.law.dto.retrieval.CaseSearchOutcome("AVAILABLE", List.of(candidate), java.util.Map.of(), List.of(), 1, 0, 1, false, false, false));
        when(api.searchCasesForLaw(any(), any(), eq("검증법"))).thenReturn(new LegalToolContracts.SearchResult(
                LegalToolContracts.Status.AVAILABLE, List.of(new LegalToolContracts.Candidate("901", null,
                "사건", null, "2026다1", "20260101", "검증법원")), 1, 10, 1, false, Instant.now(), "https://www.law.go.kr"));
    }

    @Test void foreignLawIdsAndUnsearchedCasesCannotReachApi() {
        var session = factory.create(List.of(law), true);
        assertThatThrownBy(() -> session.search(UUID.randomUUID().toString(), "쟁점", 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> session.read("901")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(api, reader);
    }

    @Test void searchUsesServerDerivedLawAndMetadataCannotBecomeCitableEvidence() {
        searchResult();
        var session = factory.create(List.of(law), true);
        assertThat(session.search(law.id(), "쟁점", 1)).containsEntry("referenceLaw", "검증법");
        assertThat(session.evidence()).containsExactly(law);
        assertThat(session.citedAdditions("판례 [[901]]")).isEmpty();
        verify(pipeline).search(any(), eq(law), eq("쟁점"), eq(1), any(), any());
    }

    @Test void storedCasesAreReadLocallyAndKeepTheirCitationIds() {
        var stored = new EvidenceItem(UUID.randomUUID().toString(), "CASE_BODY", "901", "사건", "https://www.law.go.kr",
                "저장 판결 본문", new EvidenceProvenance(UUID.randomUUID().toString(), null, null, null, null, null, null, null));
        var session = factory.create(List.of(law, stored));
        assertThat(session.evidence()).containsExactly(law);
        assertThat(session.read("901")).containsEntry("evidence", stored);
        assertThat(session.read("901")).containsEntry("evidence", stored);
        assertThat(session.evidence()).containsExactly(law, stored);
        assertThat(session.citedAdditions("[[" + stored.id() + "]]")).isEmpty();
        verifyNoInteractions(api, reader);
    }

    @Test void outageIsNotReportedAsEmptySearchAndToolLoopIsBounded() {
        when(api.searchCasesForLaw(any(), any(), anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, true));
        var session = factory.create(List.of(law), true);
        for (int i = 0; i < 8; i++) assertThat(session.search(law.id(), "쟁점", 1)).containsEntry("status", "UNAVAILABLE");
        assertThatThrownBy(() -> session.search(law.id(), "쟁점", 1)).isInstanceOf(IllegalStateException.class);
        assertThat(session.evidence()).containsExactly(law);
    }

    @Test void separateTurnsNeverShareAuthorizedCandidateIds() {
        searchResult();
        factory.create(List.of(law), true).search(law.id(), "쟁점", 1);
        assertThatThrownBy(() -> factory.create(List.of(law), true).read("901")).isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).getCaseDetail(any(), anyString());
    }
}
