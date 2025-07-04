package com.MoleLaw_backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LawDto {
    private String lawName;           // 법령명
    private String ministry;          // 소관부처
    private String lawType;           // 법령종류 (예: 법률, 대통령령)
    private String proclamationNo;    // 공포번호
    private String proclamationDate;  // 공포일자
    private String enforcementDate;   // 시행일
}
