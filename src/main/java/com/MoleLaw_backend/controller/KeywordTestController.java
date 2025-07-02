package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.service.law.ExtractKeyword;
import com.MoleLaw_backend.dto.request.QueryRequest;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.MoleLaw_backend.dto.response.KeywordAndTitleResponse;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class KeywordTestController {

    private final ExtractKeyword extractKeyword;

    @Operation(summary = "GPT 키워드 및 요약 문장 추출", description = "질문을 입력하면 GPT가 키워드와 요약 문장을 추출합니다.")
    @PostMapping("/keywords")
    public ResponseEntity<KeywordAndTitleResponse> getKeywordsAndSummary(@RequestBody QueryRequest request) {
        KeywordAndTitleResponse response = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(response);
    }
}
