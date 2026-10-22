package com.MoleLaw_backend.law.dto.retrieval;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision.Action.*;
import static org.assertj.core.api.Assertions.*;

class EvidenceDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void acceptsEachActionWithOnlyItsOwnExecutionIntent() {
        assertThat(decision(ANSWER_READY, List.of(), List.of("source-1"), List.of(), List.of()).action()).isEqualTo(ANSWER_READY);
        assertThat(decision(SEARCH_AGAIN, List.of("원상회복 의무"), List.of(), List.of("계약 해제 원상회복"), List.of()).action())
                .isEqualTo(SEARCH_AGAIN);
        assertThat(decision(COLLECT_LAW, List.of("해제 효과"), List.of(), List.of(), List.of(law())).action()).isEqualTo(COLLECT_LAW);
        assertThat(decision(INSUFFICIENT, List.of("계약 사실관계"), List.of(), List.of(), List.of()).action()).isEqualTo(INSUFFICIENT);
    }

    @Test
    void rejectsContradictoryOrIncompleteActions() {
        assertThatThrownBy(() -> decision(ANSWER_READY, List.of(), List.of(), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(ANSWER_READY, List.of("누락"), List.of("source-1"), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(SEARCH_AGAIN, List.of("누락"), List.of(), List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(COLLECT_LAW, List.of("누락"), List.of(), List.of("검색"), List.of(law()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(INSUFFICIENT, List.of("누락"), List.of(), List.of("검색"), List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesEvidenceNeverSuppliedToTheModel() {
        var decision = decision(ANSWER_READY, List.of(), List.of("invented"), List.of(), List.of());
        assertThatThrownBy(() -> decision.validateAgainst(List.of("source-1"))).hasMessage("Invalid evidence decision");
    }

    @Test
    void strictParsingRejectsBudgetFieldsMissingFieldsTrailingJsonAndUnknownActions() {
        String json = readyJson();
        assertThat(EvidenceDecision.parse(json, List.of("source-1"), mapper).action()).isEqualTo(ANSWER_READY);
        assertThatThrownBy(() -> EvidenceDecision.parse(json.replace("\"reason\":", "\"tokenBudget\":500,\"reason\":"), List.of("source-1"), mapper))
                .hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse(json.replace("\"lawSearches\":[],", ""), List.of("source-1"), mapper))
                .hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse(json + " {}", List.of("source-1"), mapper)).hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse(json.replace("ANSWER_READY", "RUN_TOOL"), List.of("source-1"), mapper))
                .hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse(json.replace("\"ANSWER_READY\"", "0"), List.of("source-1"), mapper))
                .hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse(json.replace("\"action\":", "\"action\":\"INSUFFICIENT\",\"action\":"), List.of("source-1"), mapper))
                .hasMessage("Invalid evidence decision");
        assertThatThrownBy(() -> EvidenceDecision.parse("null", List.of(), mapper)).hasMessage("Invalid evidence decision");
    }

    @Test
    void collectionPlansValidateSearchModesAndExplicitNullableMinistry() throws Exception {
        assertThatThrownBy(() -> new EvidenceDecision.LawSearch("민법", EvidenceDecision.SearchField.BODY, true, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EvidenceDecision.LawSearch("민법", EvidenceDecision.SearchField.TITLE, true, "invalid"))
                .isInstanceOf(IllegalArgumentException.class);
        var original = decision(COLLECT_LAW, List.of("원상회복 의무"), List.of(), List.of(), List.of(law()));
        assertThat(EvidenceDecision.parse(mapper.writeValueAsString(original), List.of(), mapper)).isEqualTo(original);
        assertThatThrownBy(() -> EvidenceDecision.parse(mapper.writeValueAsString(original).replace("\"exactName\":true", "\"exactName\":null"), List.of(), mapper))
                .hasMessage("Invalid evidence decision");
    }

    @Test
    void boundsAndCopiesListsAndRejectsNormalizedDuplicates() {
        var ids = new ArrayList<>(List.of("source-1"));
        var decision = decision(ANSWER_READY, List.of(), ids, List.of(), List.of());
        ids.clear();
        assertThat(decision.evidenceIds()).containsExactly("source-1");
        assertThatThrownBy(() -> decision.evidenceIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decision(ANSWER_READY, List.of(), List.of("source-1", " source-1 "), List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> decision(SEARCH_AGAIN, List.of("누락"), List.of(), List.of("a", "b", "c", "d"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private EvidenceDecision decision(EvidenceDecision.Action action, List<String> missing, List<String> ids,
                                      List<String> queries, List<EvidenceDecision.LawSearch> laws) {
        return new EvidenceDecision(action, "판단 사유", missing, ids, queries, laws);
    }

    private EvidenceDecision.LawSearch law() {
        return new EvidenceDecision.LawSearch("민법", EvidenceDecision.SearchField.TITLE, true, null);
    }

    private String readyJson() {
        return "{\"action\":\"ANSWER_READY\",\"lawSearches\":[],\"reason\":\"충분\",\"missingIssues\":[],\"evidenceIds\":[\"source-1\"],\"searchQueries\":[]}";
    }
}
