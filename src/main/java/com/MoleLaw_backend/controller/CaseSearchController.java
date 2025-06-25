package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.service.law.CaseSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/cases")
@RequiredArgsConstructor
public class CaseSearchController {

    private final CaseSearchService caseSearchService;

    @GetMapping
    public List<PrecedentInfo> getPrecedents(@ModelAttribute PrecedentSearchRequest request) {
        return caseSearchService.searchCases(request);
    }
}
