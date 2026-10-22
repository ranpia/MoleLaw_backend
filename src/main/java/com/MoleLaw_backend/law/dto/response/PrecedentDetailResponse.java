package com.MoleLaw_backend.law.dto.response;

import com.MoleLaw_backend.law.dto.response.PrecedentSummaryResponse;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "판례 상세 원문. 외부 API의 HTML 줄바꿈 등 표기를 보존합니다.")
public class PrecedentDetailResponse extends PrecedentSummaryResponse {
    @JsonProperty("판시사항")
    @Schema(description = "판결의 법적 쟁점; 미제공 시 빈 문자열 또는 null", example = "손해배상 책임의 성립 요건")
    private String issues;
    @JsonProperty("판결요지")
    @Schema(description = "판결 요지; 미제공 시 빈 문자열 또는 null", example = "귀책사유와 손해 발생을 심리하여야 한다.")
    private String summary;
    @JsonProperty("참조조문")
    @Schema(description = "참조 법령·조문", example = "민법 제750조")
    private String referencedArticles;
    @JsonProperty("참조판례")
    @Schema(description = "참조 판례; 미제공 시 빈 문자열 또는 null", example = "대법원 2020다12345 판결")
    private String referencedCases;
    @JsonProperty("판례내용")
    @Schema(description = "판례 본문; JSON 상세 조회가 지원되는 판례만 반환", example = "【주문】 원심판결을 파기한다. 【이유】 ...")
    private String content;
}
