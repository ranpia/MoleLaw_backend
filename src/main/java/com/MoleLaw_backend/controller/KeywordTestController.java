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

import java.util.List;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class KeywordTestController {

    private final ExtractKeyword extractKeyword;

    @Operation(summary = "GPT 키워드 추출", description = "질문을 입력하면 GPT가 키워드를 추출합니다.")
    @PostMapping("/keywords")
    public ResponseEntity<List<String>> getKeywords(@RequestBody QueryRequest request) {
        List<String> keywords = extractKeyword.extractKeywords(request.getQuery());
        return ResponseEntity.ok(keywords);
    }
}
