package com.MoleLaw_backend.law.service;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.law.entity.Law;
import com.MoleLaw_backend.law.entity.LawChunk;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class LegalEvidenceCollectorTest {
    private final CaseSearchService cases = mock(CaseSearchService.class);
    private final LawSimilarityService similarity = mock(LawSimilarityService.class);
    private final LegalEvidenceCollector collector = new LegalEvidenceCollector(cases, similarity);
    private final LawChunk chunk = LawChunk.builder().id(12L).law(Law.builder().id(3L).name("민법").build())
            .articleNumber("제548조").contentText("  계약 해제 시 원상회복 의무가 있다.  ").build();

    @Test
    void snapshotsStatutesWithoutFollowingLaterEntityChanges() {
        when(similarity.findSimilarChunksWithFallback("질문", 3)).thenReturn(List.of(chunk));
        when(cases.searchCases(any())).thenReturn(List.of());
        var result = collector.collect("질문");
        var evidence = result.get(0);
        assertEquals("LAW_CHUNK", evidence.kind());
        assertEquals("law-chunk:12", evidence.sourceId());
        assertDoesNotThrow(() -> java.util.UUID.fromString(evidence.id()));
        chunk.setContentText("변경된 본문");
        chunk.getLaw().setName("변경된 법령명");
        assertEquals("계약 해제 시 원상회복 의무가 있다.", evidence.content());
        assertEquals("민법 제548조", evidence.title());
    }

    @Test
    void unavailableCaseApiStillReturnsStatuteEvidence() {
        when(similarity.findSimilarChunksWithFallback("질문", 3)).thenReturn(List.of(chunk));
        when(cases.searchCases(any())).thenThrow(new OpenLawApiException(ErrorCode.OPENLAW_API_FAILURE));
        assertEquals("LAW_CHUNK", collector.collect("질문").get(0).kind());
    }

    @Test
    void emptyRetrievalFailsBeforeCaseLookup() {
        when(similarity.findSimilarChunksWithFallback("질문", 3)).thenReturn(List.of());
        assertThrows(com.MoleLaw_backend.common.ai.exception.GptApiException.class,
                () -> collector.collect("질문"));
        verifyNoInteractions(cases);
    }

    @Test
    void deduplicatesAndCapsCasesAndLabelsThemAsMetadata() {
        when(similarity.findSimilarChunksWithFallback("질문", 3)).thenReturn(List.of(chunk, chunk));
        var precedents = IntStream.range(0, 25).mapToObj(index -> {
            var precedent = new PrecedentInfo();
            precedent.setCaseId(String.valueOf(index));
            precedent.setCaseName("원상회복");
            return precedent;
        }).toList();
        when(cases.searchCases(any())).thenReturn(precedents);
        var metadata = collector.collect("질문").stream().filter(item -> item.kind().equals("CASE_METADATA")).toList();
        assertEquals(20, metadata.size());
        assertEquals(20, metadata.stream().map(item -> item.sourceId()).distinct().count());
        assertTrue(metadata.get(0).content().contains("판결 이유·판시사항 근거로 사용할 수 없음"));
    }
}
