package com.MoleLaw_backend.law.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "LawDto")
public class LawDetailResponse {
    @Schema(description = "법령명", example = "근로자퇴직급여 보장법")
    private String lawName;           // 법령명
    @Schema(description = "관련 법령의 소관 부처", example = "고용노동부")
    private String ministry;          // 소관부처
    @Schema(description = "법령 종류", example = "법률")
    private String lawType;           // 법령종류 (예: 법률, 대통령령)
    @Schema(description = "공포번호", example = "20520")
    private String proclamationNo;    // 공포번호
    @Schema(description = "공포일자", example = "20241022")
    private String proclamationDate;  // 공포일자
    @Schema(description = "시행일자", example = "20251023")
    private String enforcementDate;   // 시행일
}
