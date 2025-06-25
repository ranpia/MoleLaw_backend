package com.MoleLaw_backend.service.law;

import com.MoleLaw_backend.dto.PrecedentInfo;
import com.MoleLaw_backend.dto.request.PrecedentSearchRequest;

import java.util.List;

public interface CaseSearchService {
    List<PrecedentInfo> searchCases(PrecedentSearchRequest request);
}
