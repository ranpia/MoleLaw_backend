package com.MoleLaw_backend.util;

import com.MoleLaw_backend.dto.response.LawDto;
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

            JsonNode rows = root.path("LawList").path("row");

            if (rows.isArray()) {
                for (JsonNode row : rows) {
                    LawDto law = LawDto.builder()
                            .lawName(row.path("법령명").asText())
                            .ministry(row.path("소관부처명").asText())
                            .lawType(row.path("법령종류명").asText())
                            .proclamationNo(row.path("공포번호").asText())
                            .proclamationDate(row.path("공포일자").asText())
                            .enforcementDate(row.path("시행일자").asText())
                            .build();
                    laws.add(law);
                }
            }
        } catch (Exception e) {
            e.printStackTrace(); // 실제 운영에서는 로그 처리로
        }
        return laws;
    }
}
