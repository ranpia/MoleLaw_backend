package com.MoleLaw_backend.law.service;

import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;

import java.util.List;

public interface CaseSearchService {
    List<PrecedentInfo> searchCases(PrecedentSearchRequest request);
}
