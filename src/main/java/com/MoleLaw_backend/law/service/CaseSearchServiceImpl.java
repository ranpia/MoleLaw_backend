package com.MoleLaw_backend.law.service;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.PrecedentInfo;
import com.MoleLaw_backend.law.dto.request.PrecedentSearchRequest;
import com.MoleLaw_backend.law.dto.response.PrecedentDetailResponse;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.MoleLaw_backend.law.service.tool.OpenLawCaseClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class CaseSearchServiceImpl implements CaseSearchService {
    private final OpenLawCaseClient client;
    private final ObjectMapper mapper;

    @Override
    public List<PrecedentInfo> searchCases(PrecedentSearchRequest request) {
        if (request == null) throw new MolelawException(ErrorCode.INVALID_REQUEST);
        request.validate();
        JsonNode rows = client.search(request).path("prec");
        if (rows.isMissingNode() || rows.isNull() || (rows.isArray() && rows.isEmpty())) return List.of();
        if (!rows.isObject() && !rows.isArray()) throw invalidResponse();
        Iterable<JsonNode> entries = rows.isObject() ? List.of(rows) : rows;
        List<PrecedentInfo> results = new ArrayList<>();
        for (JsonNode row : entries) {
            PrecedentInfo result = convert(row, PrecedentInfo.class);
            if (result == null || result.getCaseId() == null || !result.getCaseId().matches("[0-9]{1,20}")
                    || result.getCaseName() == null || result.getCaseName().isBlank()) throw invalidResponse();
            results.add(result);
        }
        return results;
    }

    @Override
    public PrecedentDetailResponse getCaseDetail(String id) {
        if (id == null || !id.matches("[0-9]{1,20}")) throw new MolelawException(ErrorCode.INVALID_REQUEST);
        PrecedentDetailResponse detail = convert(client.detail(id), PrecedentDetailResponse.class);
        if (detail == null || !id.equals(detail.getCaseId()) || detail.getCaseName() == null || detail.getCaseName().isBlank()
                || detail.getContent() == null || detail.getContent().isBlank()) throw invalidResponse();
        detail.setDetailLink("https://www.law.go.kr/precInfoP.do?precSeq=" + id);
        return detail;
    }

    private <T> T convert(JsonNode row, Class<T> type) {
        try {
            return mapper.treeToValue(row, type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw invalidResponse();
        }
    }

    private static OpenLawApiException invalidResponse() {
        return new OpenLawApiException(ErrorCode.OPENLAW_INVALID_RESPONSE);
    }
}
