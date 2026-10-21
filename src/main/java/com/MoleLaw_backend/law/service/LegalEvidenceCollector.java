package com.MoleLaw_backend.law.service;

import com.MoleLaw_backend.chat.service.evidence.EvidenceItem;
import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.entity.LawChunk;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Current retrieval baseline and immutable evidence copies; never generates an answer. */
@Slf4j
@Service
@RequiredArgsConstructor
public class LegalEvidenceCollector {
    private final CaseSearchService caseSearchService;
    private final LawSimilarityService lawSimilar;

    public List<EvidenceItem> collect(String query) {
        List<LawChunk> chunks = lawSimilar.findSimilarChunksWithFallback(query, 3);
        if (chunks.isEmpty()) throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        List<PrecedentInfo> cases = new ArrayList<>();
        for (LawChunk chunk : chunks) {
            try {
                var request = new PrecedentSearchRequest();
                request.setQuery(chunk.getLaw().getName());
                cases.addAll(caseSearchService.searchCases(request));
            } catch (OpenLawApiException failure) {
                log.warn("판례 조회 실패: errorCode={}", failure.getErrorCode());
            }
        }
        List<EvidenceItem> evidence = new ArrayList<>();
        for (LawChunk chunk : chunks) {
            evidence.add(new EvidenceItem(UUID.randomUUID().toString(), "LAW_CHUNK", "law-chunk:" + chunk.getId(),
                    chunk.getLaw().getName() + " " + Optional.ofNullable(chunk.getArticleNumber()).orElse(""),
                    "https://www.law.go.kr/법령/" + chunk.getLaw().getName().replace(" ", ""),
                    chunk.getContentText().strip()));
        }
        Set<String> seenCases = new HashSet<>();
        for (PrecedentInfo precedent : cases) {
            if (precedent.getCaseId() == null || precedent.getCaseId().isBlank()
                    || seenCases.size() >= 20 || !seenCases.add(precedent.getCaseId())) continue;
            evidence.add(new EvidenceItem(UUID.randomUUID().toString(), "CASE_METADATA", precedent.getCaseId(),
                    String.valueOf(precedent.getCaseName()), "https://www.law.go.kr/precInfoP.do?precSeq=" + precedent.getCaseId(),
                    "판례 목록 메타데이터만 확보됨. 판결 이유·판시사항 근거로 사용할 수 없음. 사건번호: "
                            + precedent.getCaseNumber() + ", 선고일: " + precedent.getDecisionDate() + ", 법원: " + precedent.getCourtName()));
        }
        return List.copyOf(evidence);
    }
}
