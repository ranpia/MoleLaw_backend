package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;

@Schema(description = "법령 조문 aiSearch 조회. search=0으로 고정하며 기존 법령 목록의 TITLE/BODY 옵션과 다릅니다.")
public record AiLawSearchProbeRequest(
        @NotBlank @Size(max = 4000) @Schema(description = "Unicode 최대 2000자 검색어", example = "공항 높이 제한", requiredMode = Schema.RequiredMode.REQUIRED) String query,
        @Min(1) @Max(10000) @Schema(description = "페이지, 생략 시 1", example = "1", defaultValue = "1") Integer page,
        @Min(1) @Max(15) @Schema(description = "페이지 크기, 생략 시 15", example = "15", defaultValue = "15") Integer display) {
    public AiLawSearchRequest toToolRequest() {
        try { return new AiLawSearchRequest(query, page == null ? 1 : page, display == null ? 15 : display); }
        catch (IllegalArgumentException invalid) { throw new MolelawException(ErrorCode.INVALID_REQUEST); }
    }
}
