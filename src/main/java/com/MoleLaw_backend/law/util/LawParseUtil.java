package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.response.LawDto;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.exception.OpenLawApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

public class LawParseUtil {

    public static List<LawDto> parseJsonToLawList(String json) {
        List<LawDto> laws = new ArrayList<>();
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            JsonNode search = root == null ? null : root.get("LawSearch");
            if (search == null || !search.isObject()
                    || !search.path("totalCnt").asText().matches("[0-9]{1,9}")) {
                throw invalidResponse();
            }
            JsonNode rows = search.path("law");
            if (rows.isMissingNode() || rows.isNull() || (rows.isArray() && rows.isEmpty())) {
                if (search.path("totalCnt").asInt() == 0) return List.of();
                throw invalidResponse();
            }
            if (!rows.isObject() && !rows.isArray()) throw invalidResponse();
            if (search.path("totalCnt").asInt() == 0) throw invalidResponse();
            Iterable<JsonNode> entries = rows.isObject() ? List.of(rows) : rows;
            for (JsonNode row : entries) {
                if (!row.isObject() || row.path("법령명한글").asText().isBlank()) throw invalidResponse();
                LawDto law = LawDto.builder()
                        .lawName(row.path("법령명한글").asText())
                        .ministry(row.path("소관부처명").asText())
                        .lawType(row.path("법령구분명").asText())
                        .proclamationNo(row.path("공포번호").asText())
                        .proclamationDate(row.path("공포일자").asText())
                        .enforcementDate(row.path("시행일자").asText())
                        .build();
                laws.add(law);
            }
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw invalidResponse();
        }
        return laws;
    }

    private static OpenLawApiException invalidResponse() {
        return new OpenLawApiException(ErrorCode.OPENLAW_INVALID_RESPONSE);
    }
}
