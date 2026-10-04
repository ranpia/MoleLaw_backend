package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.domain.entity.LawChunk;
import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.dto.response.GptAnswerResponse;
import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.exception.OpenLawApiException;
import com.MoleLaw_backend.service.ai.AiChatService;
import com.MoleLaw_backend.service.ai.AiPrompts;
import org.springframework.ai.chat.messages.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FinalAnswer {

    private final CaseSearchService caseSearchService;
    private final AiChatService aiChatService;
    private final AiPrompts prompts;
    private final LawSimilarityService lawSimilar;


    public GptAnswerResponse getAnswer(String query, KeywordAndTitleResponse keywordInfo) {
        List<LawChunk> topChunks = lawSimilar.findSimilarChunksWithFallback(query, 3);

        List<PrecedentInfo> precedentResults = new ArrayList<>();
        for (LawChunk lc : topChunks) {
            try {
                PrecedentSearchRequest req = new PrecedentSearchRequest();
                req.setQuery(lc.getLaw().getName());
                precedentResults.addAll(caseSearchService.searchCases(req));
            } catch (OpenLawApiException e) {
                log.warn("판례 조회 실패: errorCode={}", e.getErrorCode());
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("사용자 질문: ").append(query).append("\n\n");
        prompt.append("다음은 관련 법령 및 판례 정보입니다. 이를 참고해 사용자 질문에 대해 명확하고 친절하게 답변해주세요.\n\n");

        prompt.append("### 관련 법령\n");
        for (LawChunk lc : topChunks) {
            prompt.append("- ")
                    .append(lc.getLaw().getName())
                    .append(" (조문: ").append(Optional.ofNullable(lc.getArticleNumber()).orElse("없음"))
                    .append(", 항: ").append(Optional.ofNullable(lc.getClauseNumber()).orElse("없음"))
                    .append(")\n")
                    .append("  - 내용: ").append(lc.getContentText().strip()).append("\n")
                    .append("  - 링크: https://www.law.go.kr/법령/" + lc.getLaw().getName().replace(" ", "") + "\n");
        }

        prompt.append("\n### 관련 판례\n");
        for (PrecedentInfo p : precedentResults) {
            prompt.append("- ").append(p.getCaseNumber()).append(": ").append(p.getCaseName())
                    .append(" (").append(p.getDecisionDate()).append(", ").append(p.getCourtName()).append(")\n");
        }

        String answer = aiChatService.generateText(prompts.getLegalSystem(),
                List.of(new UserMessage(prompt.toString())), 0.5);
        return new GptAnswerResponse(answer, buildMarkdownInfo(topChunks, precedentResults));
    }

    private String buildMarkdownInfo(List<LawChunk> chunks, List<PrecedentInfo> precedents) {
        StringBuilder md = new StringBuilder();

        md.append("## 📚 관련 법령\n\n");
        for (LawChunk lc : chunks) {
            md.append("- [**").append(lc.getLaw().getName()).append("**](https://www.law.go.kr/법령/")
                    .append(lc.getLaw().getName().replace(" ", ""))
                    .append(")\n");
            md.append("  - 조문: ").append(Optional.ofNullable(lc.getArticleNumber()).orElse("없음"))
                    .append(" / 항: ").append(Optional.ofNullable(lc.getClauseNumber()).orElse("없음"))
                    .append(" / 내용: ").append(lc.getContentText().strip()).append("\n");
        }

        md.append("\n---\n\n");
        md.append("## ⚖️ 관련 판례\n\n");
        for (PrecedentInfo p : precedents) {
            md.append("- [**").append(p.getCaseName()).append("**](https://www.law.go.kr/precInfoP.do?precSeq=")
                    .append(p.getCaseId()).append(")\n");
            md.append("  - 사건번호: ").append(p.getCaseNumber()).append(" / 선고일: ")
                    .append(p.getDecisionDate()).append(" / 법원: ").append(p.getCourtName()).append("\n");
        }

        return md.toString();
    }
}
