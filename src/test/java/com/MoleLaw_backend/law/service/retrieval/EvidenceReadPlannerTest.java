package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceReading;
import com.MoleLaw_backend.law.service.tool.EvidenceReadToolService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import java.util.List;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class EvidenceReadPlannerTest {
    private final ChatModel model = mock(ChatModel.class);
    private final EvidenceReadToolService reader = mock(EvidenceReadToolService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private EvidenceReadPlanner planner(int bodyLimit) {
        return new EvidenceReadPlanner(ChatClient.builder(model).build(), mapper, reader, 4000, bodyLimit);
    }
    private EvidenceItem item(String id, String content) {
        return new EvidenceItem(id, "LAW_AI_SEARCH", "source:" + id, "법령 " + id, "https://www.law.go.kr", content);
    }
    private ChatResponse response(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    @Test void showsAllFifteenMetadataEntriesBeforeReadingMultipleSelectedArticles() throws Exception {
        var candidates = new ArrayList<EvidenceItem>();
        for (int i = 0; i < 15; i++) candidates.add(item("id-" + i, "본문 비공개 " + i));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[\"id-14\",\"id-0\"]}"),
                response("{\"evidenceIds\":[]}"));
        when(reader.readEvidence(candidates, List.of("id-14", "id-0")))
                .thenReturn(List.of(candidates.get(14), candidates.get(0)));
        var result = planner(12000).read("질문", candidates, null);
        assertThat(result.catalog()).hasSize(15);
        assertThat(result.evidence()).containsExactly(candidates.get(14), candidates.get(0));
        var prompts = ArgumentCaptor.forClass(Prompt.class);
        verify(model, times(2)).call(prompts.capture());
        assertThat(prompts.getAllValues().get(0).getUserMessage().getText()).contains("candidateCatalog", "id-14");
        assertThat(prompts.getAllValues()).allSatisfy(prompt ->
                assertThat(prompt.getUserMessage().getText()).contains("candidateCatalog").doesNotContain("본문 비공개"));
        var second = mapper.readTree(prompts.getAllValues().get(1).getUserMessage().getText());
        assertThat(second.path("candidateCatalog").toString()).doesNotContain("id-14", "\"id-0\"");
    }

    @Test void oversizedArticleIsDeferredWholeAndAnotherReadRoundCanChooseASmallerOne() {
        var large = item("large", "긴 조문 ".repeat(1000));
        var small = item("small", "짧은 조문 전체");
        var candidates = List.of(large, small);
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[\"large\"]}"),
                response("{\"evidenceIds\":[\"small\"]}"));
        when(reader.readEvidence(candidates, List.of("large"))).thenReturn(List.of(large));
        when(reader.readEvidence(candidates, List.of("small"))).thenReturn(List.of(small));
        var result = planner(256).read("질문", candidates, null);
        assertThat(result.evidence()).containsExactly(small);
        assertThat(result.outcomes().get(0).status()).isEqualTo(EvidenceReading.Status.BODY_BUDGET_EXCEEDED);
        assertThat(large.content()).endsWith("긴 조문 ");
    }

    @Test void inventedIdsAreRejectedBeforeReadingAnySource() {
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[\"invented\"]}"));
        assertThatThrownBy(() -> planner(12000).read("질문", List.of(item("allowed", "본문")), null))
                .isInstanceOf(GptApiException.class);
        verifyNoInteractions(reader);
    }

    @Test void bodyBudgetIsCumulativeAcrossBothSelectionRounds() {
        var first = item("first", "법령 조문입니다. ".repeat(16));
        var second = item("second", "법령 조문입니다. ".repeat(16));
        var candidates = List.of(first, second);
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[\"first\"]}"),
                response("{\"evidenceIds\":[\"second\"]}"));
        when(reader.readEvidence(candidates, List.of("first"))).thenReturn(List.of(first));
        when(reader.readEvidence(candidates, List.of("second"))).thenReturn(List.of(second));
        var result = planner(256).read("질문", candidates, null);
        assertThat(result.evidence()).containsExactly(first);
        assertThat(result.outcomes().get(1).status()).isEqualTo(EvidenceReading.Status.BODY_BUDGET_EXCEEDED);
    }

    @Test void alreadyReadIdsAreSkippedWithoutLosingVerifiedEvidence() {
        var first = item("first", "본문");
        var candidates = List.of(first, item("second", "다른 본문"));
        when(model.call(any(Prompt.class))).thenReturn(response("{\"evidenceIds\":[\"first\"]}"));
        when(reader.readEvidence(candidates, List.of("first"))).thenReturn(List.of(first));
        assertThat(planner(12000).read("질문", candidates, null).evidence()).containsExactly(first);
        verify(reader, times(1)).readEvidence(anyList(), anyList());
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test void emptyCatalogNeedsNoModelOrSourceCall() {
        assertThat(planner(12000).read("질문", List.of(), null).evidence()).isEmpty();
        verify(model, never()).call(any(Prompt.class));
        verifyNoInteractions(reader);
    }
}
