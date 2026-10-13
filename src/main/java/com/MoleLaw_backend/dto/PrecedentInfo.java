package com.MoleLaw_backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrecedentInfo {

    @JsonProperty("판례일련번호")
    @Schema(description = "판례 일련번호", example = "123456")
    private String caseId;

    @JsonProperty("사건명")
    @Schema(description = "사건명", example = "퇴직금")
    private String caseName;

    @JsonProperty("사건번호")
    @Schema(description = "사건번호", example = "2024다12345")
    private String caseNumber;

    @JsonProperty("선고일자")
    @Schema(description = "선고일자", example = "2024.01.15")
    private String decisionDate;

    @JsonProperty("법원명")
    @Schema(description = "선고 법원명", example = "대법원")
    private String courtName;

    @JsonProperty("사건종류명")
    @Schema(description = "사건 종류", example = "민사")
    private String caseType;

    @JsonProperty("판결유형")
    @Schema(description = "판결 유형", example = "판결")
    private String decisionType;

    @JsonProperty("선고")
    @Schema(description = "선고 구분", example = "선고")
    private String decisionResult;

    @JsonProperty("판례상세링크")
    @Schema(description = "판례 상세 링크", example = "https://www.law.go.kr/precInfoP.do?precSeq=123456")
    private String detailLink;
}
