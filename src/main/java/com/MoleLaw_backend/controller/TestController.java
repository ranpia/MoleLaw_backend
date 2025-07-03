package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.dto.request.QueryRequest;
import com.MoleLaw_backend.dto.response.AnswerResponse;
import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;
import com.MoleLaw_backend.service.law.CaseSearchService;
import com.MoleLaw_backend.service.law.ExtractKeyword;
import com.MoleLaw_backend.service.law.FinalAnswer;
import com.MoleLaw_backend.service.law.LawSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
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

    @GetMapping("/lawsearch")
    @Operation(summary = "키워드 기반 법률제목 조회", description = "openlaw api 활용한 법률조회")
    public ResponseEntity<String> searchLaws(@RequestParam String keyword) {
        String resultXml = lawSearchService.searchLawByKeyword(keyword);
        return ResponseEntity.ok(resultXml);
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
        return ResponseEntity.ok(finalAnswer.getAnswer(request.getQuery(), request.getKeywords()));
    }
}
