package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record LawReindexTestRequest(
        @NotNull @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        @Schema(description = "저장된 법령 원문 UUID. index 응답의 referenceId", example = "11111111-1111-1111-1111-111111111111")
        String referenceId) {
}
