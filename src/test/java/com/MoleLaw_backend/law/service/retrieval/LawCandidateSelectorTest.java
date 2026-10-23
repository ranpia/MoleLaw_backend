package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LawCandidateSelectorTest {
    private final ChatModel model = mock(ChatModel.class);
    private final LawCandidateSelector selector = new LawCandidateSelector(ChatClient.builder(model).build(), new ObjectMapper());
    private final EvidenceDecision.LawSearch condition = new EvidenceDecision.LawSearch("공항", EvidenceDecision.SearchField.BODY, false, null);
    private final List<Candidate> candidates = List.of(
            new Candidate("1", "1", "행사 지원법", "20260101", null, null, null),
            new Candidate("2", "2", "공항 관련법", "20260101", null, null, null));

    private void respond(String value) {
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(value)))));
    }

    @Test void selectsOnlyReviewedCandidateInsteadOfProviderFirstResult() {
        respond("{\"rankings\":[{\"candidateId\":\"1\",\"score\":10,\"reason\":\"무관\"},{\"candidateId\":\"2\",\"score\":95,\"reason\":\"직접 관련\"}]}");
        assertThat(selector.select("공항 건축", null, condition, candidates)).containsExactly(candidates.get(1));
    }

    @Test void mayRejectAnEntirePageWithoutCollectingAnything() {
        respond("{\"rankings\":[{\"candidateId\":\"1\",\"score\":20,\"reason\":\"무관\"},{\"candidateId\":\"2\",\"score\":69,\"reason\":\"불확실\"}]}");
        assertThat(selector.select("공항 건축", null, condition, candidates)).isEmpty();
    }

    @Test void rejectsInventedIdsDuplicateIdsAndUnknownFieldsWithoutRetry() {
        for (String value : List.of("{\"reason\":\"선택\",\"candidateIds\":[\"unknown\"]}",
                "{\"reason\":\"선택\",\"candidateIds\":[\"2\",\"2\"]}",
                "{\"reason\":\"선택\",\"candidateIds\":[],\"execute\":true}")) {
            respond(value);
            assertThatThrownBy(() -> selector.select("공항 건축", null, condition, candidates)).isInstanceOf(GptApiException.class);
        }
        verify(model, times(3)).call(any(Prompt.class));
    }

    @Test void emptyPageDoesNotCallTheModel() {
        assertThat(selector.select("질문", null, condition, List.of())).isEmpty();
        verify(model, never()).call(any(Prompt.class));
    }

    @Test void sortsScoresInsteadOfModelOrProviderOrder() {
        respond("{\"rankings\":[{\"candidateId\":\"1\",\"score\":70,\"reason\":\"관련\"},{\"candidateId\":\"2\",\"score\":95,\"reason\":\"직접 관련\"}]}");
        assertThat(selector.select("질문", null, condition, candidates))
                .containsExactly(candidates.get(1), candidates.get(0));
    }

    @Test void rejectsIncompleteAndOutOfRangeRankings() {
        for (String value : List.of("{\"rankings\":[]}",
                "{\"rankings\":[{\"candidateId\":\"1\",\"score\":101,\"reason\":\"관련\"}]}")) {
            respond(value);
            assertThatThrownBy(() -> selector.select("질문", null, condition, candidates))
                    .isInstanceOf(GptApiException.class);
        }
    }
}
