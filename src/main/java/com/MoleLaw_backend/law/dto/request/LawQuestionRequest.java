package com.MoleLaw_backend.law.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(name = "QueryRequest")
public class LawQuestionRequest {
    @NotBlank
    @Size(max = 4000)
    @Schema(description = "공백이 아닌 법률 질문. Unicode 최대 2000자·UTF-8 최대 32KiB이며 서버에서 코드포인트·byte 수를 검증합니다.", example = "퇴직금 미지급", requiredMode = Schema.RequiredMode.REQUIRED, maxLength = 2000)
    private String query;
}
