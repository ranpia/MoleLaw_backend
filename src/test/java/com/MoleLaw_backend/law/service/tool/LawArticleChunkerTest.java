package com.MoleLaw_backend.law.service.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LawArticleChunkerTest {
    private final LawArticleChunker chunker = new LawArticleChunker(new ObjectMapper());

    @Test
    void preservesNestedArticleOrderAndDoesNotRepeatParagraphNumber() {
        String json = """
                {"기본정보":{"법령명_한글":"민법"},"조문":{"조문단위":{
                  "조문번호":"2","조문가지번호":"1","조문제목":"신의성실",
                  "조문내용":"제2조의1(신의성실)","항":[
                    {"항번호":"①","항내용":"① 권리의 행사는 신의에 좇아야 한다.",
                     "호":{"호번호":"1.","호내용":"권리 행사", "목":[
                       {"목번호":"가.","목내용":"성실히 이행한다."},
                       {"목번호":"나.","목내용":"남용하지 않는다."}]}},
                    {"항번호":"②","항내용":"의무를 이행한다."}]}}}
                """;
        List<LawArticleChunker.Chunk> result = chunker.chunk(json, "민법", 300, 64);
        assertThat(result).hasSize(1);
        assertThat(result.get(0).unitId()).isEqualTo("2-1");
        assertThat(result.get(0).title()).isEqualTo("민법 제2조의1(신의성실)");
        assertThat(result.get(0).articleText()).isEqualTo(
                "제2조의1(신의성실) ① 권리의 행사는 신의에 좇아야 한다. 1. 권리 행사 가. 성실히 이행한다. 나. 남용하지 않는다. ② 의무를 이행한다.");
    }

    @Test
    void acceptsArrayAndSkipsSectionHeadings() {
        String json = """
                {"조문":{"조문단위":[
                  {"조문여부":"전문","조문내용":"제1장 총칙"},
                  {"조문번호":"1","조문가지번호":"0","조문내용":["제1조", "  목적\n 내용을 정한다. "]},
                  {"조문번호":"2","조문내용":"두 번째 조문"}]}}
                """.replace("목적\n", "목적\\n");
        List<LawArticleChunker.Chunk> result = chunker.chunk(json, "법률", 100, 0);
        assertThat(result).extracting(LawArticleChunker.Chunk::unitId).containsExactly("1", "2");
        assertThat(result.get(0).text()).isEqualTo("제1조 목적 내용을 정한다.");
    }

    @Test
    void splitsByCodePointWithOverlapWithoutBreakingSupplementaryCharacters() {
        String json = "{\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\"가😀나😀다😀라\"}}}";
        List<LawArticleChunker.Chunk> result = chunker.chunk(json, "법률", 3, 1);
        assertThat(result).extracting(LawArticleChunker.Chunk::text)
                .containsExactly("가😀나", "나😀다", "다😀라");
        assertThat(result).extracting(LawArticleChunker.Chunk::chunkIndex).containsExactly(0, 1, 2);
    }

    @Test
    void rejectsDuplicateArticlesAndEmptyDocuments() {
        assertThatThrownBy(() -> chunker.chunk("""
                {"조문":{"조문단위":[{"조문번호":"1","조문내용":"하나"},
                {"조문번호":"1","조문내용":"둘"}]}}
                """, "법률", 300, 64)).isInstanceOf(IllegalArgumentException.class);
        for (String json : List.of("{}", "null", "[]", "invalid",
                "{\"조문\":{\"조문단위\":{\"조문번호\":\"1\",\"조문내용\":\" \"}}}")) {
            assertThatThrownBy(() -> chunker.chunk(json, "법률", 300, 64))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsUnboundedInputsAndChunkCounts() throws Exception {
        for (int[] config : List.of(new int[]{0, 0}, new int[]{2001, 0}, new int[]{3, 3}, new int[]{3, -1})) {
            assertThatThrownBy(() -> chunker.chunk("{}", "법률", config[0], config[1]))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> chunker.chunk("가".repeat(800_000), "법률", 300, 64))
                .isInstanceOf(IllegalArgumentException.class);
        String tooLong = new ObjectMapper().writeValueAsString(java.util.Map.of("조문",
                java.util.Map.of("조문단위", java.util.Map.of("조문번호", "1", "조문내용", "가".repeat(100_001)))));
        assertThatThrownBy(() -> chunker.chunk(tooLong, "법률", 300, 64))
                .isInstanceOf(IllegalArgumentException.class);
        String tooMany = tooLong.replace("가".repeat(100_001), "가".repeat(8193));
        assertThatThrownBy(() -> chunker.chunk(tooMany, "법률", 1, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsLargeStatuteWithMoreThanOneThousandArticles() throws Exception {
        java.util.List<java.util.Map<String, String>> articles = new java.util.ArrayList<>();
        for (int number = 1; number <= 1200; number++) {
            articles.add(java.util.Map.of("조문번호", Integer.toString(number), "조문내용", "권리와 의무를 정한다."));
        }
        String json = new ObjectMapper().writeValueAsString(java.util.Map.of("조문", java.util.Map.of("조문단위", articles)));
        assertThat(chunker.chunk(json, "민법", 300, 64)).hasSize(1200);
    }
}
