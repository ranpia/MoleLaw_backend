package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.client.openlaw.OpenLawAiSearchClient;
import com.MoleLaw_backend.law.dto.retrieval.AiLawArticle;
import com.MoleLaw_backend.law.dto.retrieval.AiLawArticlePage;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.exception.LegalToolException;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class LawAiSearchToolServiceTest {
    private final OpenLawAiSearchClient client = mock(OpenLawAiSearchClient.class);
    private final LawAiSearchToolService tool = new LawAiSearchToolService(client);

    private LegalToolContext context(LegalToolContext.Purpose purpose, int limit) {
        return new LegalToolContext(purpose, "run", Instant.now().plusSeconds(30), limit, Clock.systemUTC());
    }

    @Test void buildsServerEvidenceWithBranchNumberAndSafeOfficialLink() {
        when(client.search(any())).thenReturn(new AiLawArticlePage(1, false, List.of(
                new AiLawArticle("999", "001234", "123", "검증법", "20260101", "34", "2", "제한", "본문"))));
        var context = context(LegalToolContext.Purpose.FIRST_QUESTION, 2);
        var page = tool.search(context, new AiLawSearchRequest("검색", 1, 15));
        var evidence = page.evidence().get(0);
        assertThat(evidence.title()).isEqualTo("검증법 제34조의2(제한)");
        assertThat(evidence.kind()).isEqualTo("LAW_AI_SEARCH");
        assertThat(evidence.sourceId()).isEqualTo("123:999");
        assertThat(evidence.sourceUrl()).isEqualTo("https://www.law.go.kr/법령?lsiSeq=123");
        assertThat(context.callsUsed()).isEqualTo(1);
    }

    @Test void followUpCannotInvokeRemoteSearch() {
        assertThatThrownBy(() -> tool.search(context(LegalToolContext.Purpose.FOLLOW_UP, 1), new AiLawSearchRequest("검색", 1, 15)))
                .isInstanceOfSatisfying(LegalToolException.class, ex -> assertThat(ex.getReason()).isEqualTo(LegalToolException.Reason.FORBIDDEN));
        verifyNoInteractions(client);
    }
}
