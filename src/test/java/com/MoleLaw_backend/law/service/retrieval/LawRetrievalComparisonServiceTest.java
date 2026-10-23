package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.dto.retrieval.AiLawSearchPage;
import com.MoleLaw_backend.law.dto.response.LawRetrievalComparisonResponse.Status;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.service.tool.LawAiSearchToolService;
import com.MoleLaw_backend.law.service.tool.LawRagSearchToolService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class LawRetrievalComparisonServiceTest {
    private final LawAiSearchToolService ai = mock(LawAiSearchToolService.class);
    private final LawRagSearchToolService rag = mock(LawRagSearchToolService.class);
    @SuppressWarnings("unchecked") private final ObjectProvider<LawRagSearchToolService> provider = mock(ObjectProvider.class);
    private final LawRetrievalComparisonService service = new LawRetrievalComparisonService(ai, provider);

    @Test void apiFailureDoesNotHideTheQdrantResultOrRetryEitherBackend() {
        when(provider.getIfAvailable()).thenReturn(rag);
        when(ai.search(any(), any())).thenThrow(new LegalToolException(LegalToolException.Reason.API_FAILURE, false));
        when(rag.searchTop15(any(), anyString())).thenReturn(List.of());
        var result = service.compare("공항");
        assertThat(result.aiSearch().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.aiSearch().errorCode()).isEqualTo("API_FAILURE");
        assertThat(result.qdrant().status()).isEqualTo(Status.EMPTY);
        verify(ai, times(1)).search(any(), any());
        verify(rag, times(1)).searchTop15(any(), eq("공항"));
    }

    @Test void unconfiguredQdrantIsNotReportedAsAnEmptyIndex() {
        when(ai.search(any(), any())).thenReturn(new AiLawSearchPage(1, 15, 0, false, List.of()));
        var result = service.compare("공항");
        assertThat(result.aiSearch().status()).isEqualTo(Status.EMPTY);
        assertThat(result.qdrant().status()).isEqualTo(Status.UNAVAILABLE);
        assertThat(result.qdrant().errorCode()).isEqualTo("NOT_CONFIGURED");
    }
}
