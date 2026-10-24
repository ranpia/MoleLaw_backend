package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.config.CaseSearchProperties;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CaseCandidateRerankerTest {
    private final ChatModel model = mock(ChatModel.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final CaseSearchProperties properties = new CaseSearchProperties();
    private final CaseCandidateReranker reranker = new CaseCandidateReranker(ChatClient.builder(model).build(), mapper, properties);
    private final EvidenceItem law = new EvidenceItem("law", "LAW_CHUNK", "1:61", "근로기준법 제61조", "https://www.law.go.kr", "법령 본문");

    @Test void ranksParsedJudgmentContentWithBoundedExcerptAndExactCandidateMembership() throws Exception {
        String raw = mapper.writeValueAsString(java.util.Map.of("판결요지", "사용 촉진 관련 판단", "판례내용", "앞부분" + "가".repeat(6000) + "마지막판단"));
        var item = new EvidenceItem("citation-id", "CASE_BODY", "101", "임금", "https://www.law.go.kr", raw);
        when(model.call(any(Prompt.class))).thenReturn(response("{\"rankings\":[{\"candidateId\":\"101\",\"score\":91,\"reason\":\"쟁점 직접 관련\"}]}"));
        assertThat(reranker.rank("연차 사용촉진", law, List.of(item)).get(0).score()).isEqualTo(91);
        var captor = ArgumentCaptor.forClass(Prompt.class);
        verify(model).call(captor.capture());
        var input = mapper.readTree(captor.getValue().getUserMessage().getText());
        String excerpt = input.path("judgments").get(0).path("bodyExcerpt").asText();
        assertThat(excerpt).contains("사용 촉진 관련 판단", "마지막판단", "중간 생략").doesNotContain("citation-id");
        assertThat(excerpt.length()).isLessThan(properties.getExcerptChars() + 100);
    }

    @Test void inventedIdsDuplicateRanksMissingRanksAndInvalidScoresFailClosed() {
        var item = new EvidenceItem("uuid", "CASE_BODY", "101", "임금", "https://www.law.go.kr", "판결 본문");
        for (String output : List.of("{\"rankings\":[]}",
                "{\"rankings\":[{\"candidateId\":\"999\",\"score\":90,\"reason\":\"관련\"}]}",
                "{\"rankings\":[{\"candidateId\":\"101\",\"score\":101,\"reason\":\"관련\"}]}",
                "{\"rankings\":[{\"candidateId\":\"101\",\"score\":70.5,\"reason\":\"관련\"}]}")) {
            when(model.call(any(Prompt.class))).thenReturn(response(output));
            assertThatThrownBy(() -> reranker.rank("쟁점", law, List.of(item))).isInstanceOf(GptApiException.class);
        }
    }

    private ChatResponse response(String text) { return new ChatResponse(List.of(new Generation(new AssistantMessage(text)))); }
}
