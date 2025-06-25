package com.MoleLaw_backend.controller;

import lombok.RequiredArgsConstructor;
import com.MoleLaw_backend.service.law.LawSearchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/laws")
public class LawSearchController {

    private final LawSearchService lawSearchService;

    @GetMapping("/search")
    public ResponseEntity<String> searchLaws(@RequestParam String keyword) {
        String resultXml = lawSearchService.searchLawByKeyword(keyword);
        return ResponseEntity.ok(resultXml);
    }
}
