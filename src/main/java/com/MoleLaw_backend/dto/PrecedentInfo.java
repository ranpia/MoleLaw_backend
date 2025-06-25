package com.MoleLaw_backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrecedentInfo {

    @JsonProperty("판례일련번호")
    private String caseId;

    @JsonProperty("사건명")
    private String caseName;

    @JsonProperty("사건번호")
    private String caseNumber;

    @JsonProperty("선고일자")
    private String decisionDate;

    @JsonProperty("법원명")
    private String courtName;

    @JsonProperty("사건종류명")
    private String caseType;

    @JsonProperty("판결유형")
    private String decisionType;

    @JsonProperty("선고")
    private String decisionResult;

    @JsonProperty("판례상세링크")
    private String detailLink;
}
