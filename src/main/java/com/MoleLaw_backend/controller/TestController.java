package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.domain.entity.Law;
import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.FinalAnswerRequest;
import com.MoleLaw_backend.dto.request.FirstMessageRequest;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.dto.request.QueryRequest;
import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.dto.response.LawDto;
import com.MoleLaw_backend.service.law.*;
import com.MoleLaw_backend.service.security.CustomUserDetails;
import com.MoleLaw_backend.util.LawParseUtil;
import com.MoleLaw_backend.util.MinistryCodeMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Test", description = "기능 구현 전 test API")
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/test")
public class TestController {

    private final LawSearchService lawSearchService;
    private final ExtractKeyword extractKeyword;
    private final FinalAnswer finalAnswer;
    private final CaseSearchService caseSearchService;
    private final LawEmbeddingService lawEmbeddingService;

    @GetMapping("/lawsearch")
    @Operation(summary = "키워드 기반 법률제목 조회", description = "OpenLaw API 활용한 법률 조회")
    @SecurityRequirement(name = "BearerAuth")
    public ResponseEntity<List<LawDto>> searchLaws(
            @RequestParam String keyword,
            @RequestParam(required = false) String orgCode
    ) {
        String rawJson = (orgCode == null)
                ? lawSearchService.searchLawByKeyword(keyword)
                : lawSearchService.searchLawByKeyword(keyword, orgCode);

        List<LawDto> lawList = LawParseUtil.parseJsonToLawList(rawJson);
        return ResponseEntity.ok(lawList);
    }

    @PostMapping("/law/keyword")
    @Operation(summary = "법률 키워드 및 부처 기반 검색")
    public ResponseEntity<String> getLawByKeyword(@RequestBody FirstMessageRequest request,
                                                  @AuthenticationPrincipal CustomUserDetails userDetails) {

        KeywordAndTitleResponse extracted = extractKeyword.extractKeywords(request.getContent());
        String keyword = extracted.getKeywords().get(0); // 첫 번째 키워드 기준
        String orgCode = MinistryCodeMapper.getCode(extracted.getMinistry());

        String result = (orgCode != null)
                ? lawSearchService.searchLawByKeyword(keyword, orgCode)
                : lawSearchService.searchLawByKeyword(keyword);

        return ResponseEntity.ok(result);
    }


    @Operation(summary = "GPT 키워드 및 요약 문장 추출", description = "질문을 입력하면 GPT가 키워드와 요약 문장을 추출합니다.")
    @PostMapping("/keywords")
    public ResponseEntity<KeywordAndTitleResponse> getKeywordsAndSummary(@RequestBody QueryRequest request) {
        KeywordAndTitleResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/cases")
    @Operation(summary = "법률 기반 판례 조회", description = "법률제목 기반 판례 조회")
    public List<PrecedentInfo> getPrecedents(@ModelAttribute PrecedentSearchRequest request) {
        return caseSearchService.searchCases(request);
    }

    @PostMapping("/answer")
    @Operation(summary = "gpt응답 테스트용 컨트롤러", description = "gpt 법률기반 응답 테스트")
    public ResponseEntity<AnswerResponse> getAnswer(@RequestBody QueryRequest request) {
        KeywordAndTitleResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(finalAnswer.getAnswer(request.getQuery(), response));
    }

    @PostMapping("/import")
    public ResponseEntity<String> importLaw(@RequestParam String name) {
        Law law = lawSearchService.saveLawWithArticles(name);
        return ResponseEntity.ok("저장 완료: " + law.getName());
    }

    @PostMapping("/embed")
    public ResponseEntity<String> embedLaw(@RequestParam Long lawId) {
        lawEmbeddingService.embedAllChunksForLaw(lawId);
        return ResponseEntity.ok("벡터화 완료");
    }

}
