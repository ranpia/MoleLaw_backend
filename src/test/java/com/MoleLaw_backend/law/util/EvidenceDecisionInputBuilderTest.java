package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.ArrayList;
import com.MoleLaw_backend.law.dto.retrieval.RetrievalAttempt;
import static org.assertj.core.api.Assertions.*;

class EvidenceDecisionInputBuilderTest {
    @Test
    void readBodiesHaveTheirOwnBudgetAndAreNeverSilentlyDroppedFromDecisionInput() {
        var mapper = new ObjectMapper();
        var large = item("read-long", "전체 조문 ".repeat(400));
        var second = item("read-second", "다른 법령의 전체 조문");
        var catalog = mapper.createArrayNode().add(mapper.createObjectNode().put("id", "unread"));
        var reading = new com.MoleLaw_backend.law.dto.retrieval.EvidenceReading(catalog,
                List.of(large, second), List.of());
        var input = inputs.buildRead("질문", reading, null, List.of(), 4, 12000);
        assertThat(input.path("evidence")).hasSize(2);
        assertThat(input.path("evidence").get(0).path("content").asText()).isEqualTo(large.content());
        assertThat(input.path("candidateCatalog").toString()).contains("unread");
        assertThatThrownBy(() -> inputs.buildRead("질문", reading, null, List.of(), 4, 256))
                .isInstanceOf(IllegalArgumentException.class);
    }
    final EvidenceDecisionInputBuilder inputs = new EvidenceDecisionInputBuilder(new ObjectMapper(), 256);
    EvidenceItem item(String id, String content) {
        return new EvidenceItem(id, "LAW_CHUNK", "source:" + id, "검증 조문", "https://www.law.go.kr", content);
    }

    @Test
    void skipsOversizedArticlesWithoutTruncatingAndKeepsLaterWholeArticles() {
        var longArticle = item("large", "긴 법령 본문 ".repeat(1000));
        var shortArticle = item("small", "완전한 짧은 조문");
        var input = inputs.build("질문", List.of(longArticle, shortArticle));
        assertThat(input.path("evidence")).hasSize(1);
        assertThat(input.path("evidence").get(0).path("id").asText()).isEqualTo("small");
        assertThat(input.path("evidence").get(0).path("content").asText()).isEqualTo(shortArticle.content());
        assertThat(input.toString()).doesNotContain("sourceUrl", "https://");
        assertThat(longArticle.content()).hasSize("긴 법령 본문 ".length() * 1000);
    }

    @Test
    void feedbackHistoryIsBoundedButTheLatestObservationAndWholeEvidenceRemain() {
        var attempts = new ArrayList<RetrievalAttempt>();
        for (int index = 0; index < 20; index++) {
            attempts.add(new RetrievalAttempt(RetrievalAttempt.Kind.VECTOR_SEARCH,
                    "검색 질의 " + index + " 용어 ".repeat(8), null, null,
                    RetrievalAttempt.Outcome.NO_NEW_EVIDENCE, 15, false));
        }
        var input = new EvidenceDecisionInputBuilder(new ObjectMapper(), 512)
                .build("질문", List.of(item("id", "완전한 조문")), null, attempts);
        assertThat(input.path("searchAttempts").size()).isBetween(1, 8);
        assertThat(input.path("executionState").path("vectorSearchCount").asInt()).isEqualTo(20);
        assertThat(input.path("executionState").path("lawSearchCount").asInt()).isZero();
        var latest = input.path("searchAttempts").get(input.path("searchAttempts").size() - 1);
        assertThat(latest.path("query").asText()).startsWith("검색 질의 19");
        assertThat(input.path("evidence").get(0).path("content").asText()).isEqualTo("완전한 조문");
    }

    @Test
    void emptyIndexIsAllowedButAnOversizedQuestionOrUnrepresentableEvidenceStopsBeforeLlm() {
        assertThat(inputs.build("질문", List.of()).path("evidence")).isEmpty();
        assertThatThrownBy(() -> inputs.build("질문".repeat(1000), List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> inputs.build("질문", List.of(item("large", "법령".repeat(1000)))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
