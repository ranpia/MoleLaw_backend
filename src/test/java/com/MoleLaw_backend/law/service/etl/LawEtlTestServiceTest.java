package com.MoleLaw_backend.law.service.etl;

import com.MoleLaw_backend.law.dto.request.*;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.*;
import com.MoleLaw_backend.law.dto.vector.LawIndexResult;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.service.tool.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LawEtlTestServiceTest {
    final LegalApiToolService api = mock(LegalApiToolService.class);
    final LawVectorizationToolService writer = mock(LawVectorizationToolService.class);
    final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    final LawEtlTestService service = new LawEtlTestService(api, writer, rag);

    @Test
    void continuesPastFilteredEmptyPageAndReturnsCursorWithoutSkippingCandidates() {
        when(api.searchLaws(any(), any())).thenAnswer(call -> {
            SearchRequest request = call.getArgument(1);
            assertThat(request.display()).isEqualTo(1);
            assertThat(request.lawFilters().exactName()).isTrue();
            return new SearchResult(request.page() == 1 ? Status.EMPTY : Status.AVAILABLE,
                    request.page() == 1 ? List.of() : List.of(new Candidate("123", "1", "민법", "20260101", null, null, null)),
                    request.page(), 1, 5, true, Instant.now(), "https://www.law.go.kr");
        });
        when(api.getLawDetail(any(), eq("123"))).thenReturn(new DetailResult("ref", "123", "1", "20260101",
                "민법", 1, true, Instant.now(), "https://www.law.go.kr", List.of("1")));
        when(writer.vectorizeLaw(any(), eq("ref"))).thenReturn(new LawIndexResult("ref", "collection", 1, 1));
        var result = service.collect(new LawEtlTestRequest("민법", 1, 3, null));
        assertThat(result.pagesVisited()).isEqualTo(3);
        assertThat(result.nextPage()).isEqualTo(4);
        assertThat(result.indexes()).hasSize(1);
        verify(writer, times(1)).vectorizeLaw(any(), eq("ref"));
    }

    @Test
    void normalEmptySearchEndsWithoutVectorization() {
        when(api.searchLaws(any(), any())).thenReturn(new SearchResult(Status.EMPTY, List.of(), 1, 1, 0, false,
                Instant.now(), "https://www.law.go.kr"));
        var result = service.collect(new LawEtlTestRequest("없는법", 1, 3, null));
        assertThat(result.indexes()).isEmpty();
        assertThat(result.nextPage()).isNull();
        verifyNoInteractions(writer, rag);
    }

    @Test
    void retryAndSearchDoNotCollectAgainAndBusyIsTranslated() {
        service.reindex("ref");
        service.search("신의성실");
        verify(writer).reindexStoredLaw(any(), eq("ref"));
        verify(rag).searchTop15(any(), eq("신의성실"));
        verifyNoInteractions(api);
        when(rag.searchTop15(any(), anyString())).thenThrow(new LegalToolException(LegalToolException.Reason.BUSY, true));
        assertThatThrownBy(() -> service.search("신의성실")).hasMessageContaining("임베딩 처리량");
    }
}
