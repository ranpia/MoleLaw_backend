package com.MoleLaw_backend.dto.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PrecedentSearchRequest {
    private String query; // 예: "근로기준법", "제34조", "퇴직금", "해고"
}
