package com.MoleLaw_backend.law.service.tool;

import com.MoleLaw_backend.law.client.openlaw.OpenLawAiSearchClient;
import com.MoleLaw_backend.law.dto.retrieval.AiLawSearchPage;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.evidence.EvidenceProvenance;
import org.springframework.stereotype.Service;
import lombok.RequiredArgsConstructor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Server-invoked, read-only article tool. Never registered on the follow-up client. */
@Service
@RequiredArgsConstructor
public class LawAiSearchToolService {
    private final OpenLawAiSearchClient client;

    public AiLawSearchPage search(LegalToolContext context, AiLawSearchRequest request) {
        synchronized (context) {
            context.authorize();
            context.reserve();
            var page = client.search(request);
            context.checkDeadline();
            var evidence = page.articles().stream().map(article -> {
                String sourceId = article.mst() + ":" + article.articleId();
                String id = UUID.nameUUIDFromBytes((context.executionId() + "|" + sourceId + "|"
                        + article.effectiveDate() + "|" + article.content()).getBytes(StandardCharsets.UTF_8)).toString();
                return new EvidenceItem(id, "LAW_AI_SEARCH", sourceId,
                        article.lawName() + " 제" + article.articleNumber() + "조"
                                + (article.articleBranchNumber() == null ? "" : "의" + article.articleBranchNumber())
                                + (article.articleTitle() == null ? "" : "(" + article.articleTitle() + ")"),
                        "https://www.law.go.kr/법령?lsiSeq=" + article.mst(), article.content(),
                        new EvidenceProvenance(null, article.mst(), article.lawId(), article.effectiveDate(),
                                article.articleId(), null, null, null));
            }).toList();
            return new AiLawSearchPage(request.page(), request.display(), page.totalCount(), page.hasNext(), evidence);
        }
    }
}
