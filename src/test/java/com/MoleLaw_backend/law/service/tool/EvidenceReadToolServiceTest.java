package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import com.MoleLaw_backend.law.entity.LegalSource;
import com.MoleLaw_backend.law.service.source.LegalSourceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceReadToolServiceTest {
    private final LegalSourceService sources = mock(LegalSourceService.class);
    private final EvidenceReadToolService reader = new EvidenceReadToolService(sources, new ObjectMapper());
    private final LegalSource source = new LegalSource("LAW", "123", "1", "20260101",
            "https://www.law.go.kr/법령?lsiSeq=123", "a".repeat(64), """
            {"기본정보":{"법령명_한글":"검증법"},"조문":{"조문단위":{
            "조문번호":"1","조문내용":"본문 전체와 단서를 확인한다.","항":{"항번호":"①","항내용":"예외를 포함한다."}}}}
            """);

    private EvidenceItem item(String content) {
        return new EvidenceItem("allowed", "LAW_QDRANT", "123:1", "검증법 제1조", source.getSourceUrl(), content,
                new EvidenceProvenance(source.getId(), "123", "1", "20260101", "1", "revision", "fingerprint", "generation"));
    }

    @Test void readsWholeArticleAndItsExceptionFromAuthorizedSavedVersion() {
        when(sources.restore(source.getId())).thenReturn(source);
        var expected = item("본문 전체와 단서를 확인한다. ① 예외를 포함한다.");
        assertThat(reader.readEvidence(List.of(expected), List.of("allowed"))).containsExactly(expected);
    }

    @Test void unknownIdsCannotReadAnySavedSource() {
        assertThatThrownBy(() -> reader.readEvidence(List.of(item("본문")), List.of(source.getId())))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sources);
    }

    @Test void mismatchedOrChangedArticleBodyCannotAuthorizeACitation() {
        when(sources.restore(source.getId())).thenReturn(source);
        assertThatThrownBy(() -> reader.readEvidence(List.of(item("다른 조문 본문")), List.of("allowed")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void anotherEffectiveVersionCannotBeSubstitutedForTheAuthorizedCandidate() {
        when(sources.restore(source.getId())).thenReturn(source);
        var wrongVersion = new EvidenceItem("allowed", "LAW_QDRANT", "123:1", "검증법 제1조", source.getSourceUrl(), "본문",
                new EvidenceProvenance(source.getId(), "123", "1", "20270101", "1", "revision", "fingerprint", "generation"));
        assertThatThrownBy(() -> reader.readEvidence(List.of(wrongVersion), List.of("allowed")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
