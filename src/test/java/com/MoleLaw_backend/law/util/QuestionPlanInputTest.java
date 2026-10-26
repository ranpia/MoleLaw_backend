package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.QuestionAnalysis;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import static org.assertj.core.api.Assertions.assertThat;

class QuestionPlanInputTest {
    @Test void downstreamRolesKeepIssuesAndRelationsWithoutDuplicatingRawQuotes() throws Exception {
        String raw = "사용자가 제공한 긴 상황 설명과 별도로 마지막 질문의 적용 시점도 확인해 주세요.".repeat(8);
        var analysis = new QuestionAnalysis("상담 제목", "관계와 적용 시점", List.of(new QuestionAnalysis.Fact("상황 설명", raw)),
                List.of(new QuestionAnalysis.Issue("Q1", "기준의 관계", raw, List.of("기준 관계"), List.of(), List.of("명칭 가설"), List.of()),
                        new QuestionAnalysis.Issue("Q2", "마지막 질문의 적용 시점", raw, List.of("적용 시점"), List.of(), List.of(), List.of("행위 시점"))),
                List.of(new QuestionAnalysis.Relation("Q1", "Q2", QuestionAnalysis.RelationType.TEMPORAL)), List.of("대상 명칭"));
        var plan = new QuestionRetrievalPlan(QuestionRetrievalPlan.Type.LAW_EXPLANATION,
                QuestionRetrievalPlan.ResponseScope.GENERAL_GUIDANCE, List.of("관계"), List.of(), List.of(), analysis);
        var mapper = new ObjectMapper();
        var view = QuestionPlanInput.view(plan, mapper);
        assertThat(view.path("analysis").path("issues")).hasSize(2);
        assertThat(view.toString()).contains("명칭 가설", "행위 시점", "TEMPORAL", "Q2").doesNotContain("sourceQuote", raw);
        assertThat(plan.analysis().issues().get(1).sourceQuote()).isEqualTo(raw);
        var tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);
        int prompt = tokens.estimate(Files.readString(Path.of("src/main/resources/prompts/question-retrieval-plan.txt")));
        int full = tokens.estimate(mapper.writeValueAsString(plan));
        int compact = tokens.estimate(view.toString());
        System.out.printf("Question analysis token estimate: staticPrompt=%d fullPlan=%d downstreamPlan=%d%n", prompt, full, compact);
        assertThat(compact).isLessThan(full);
    }
}
