package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;

@Getter
@Schema(description = "본인 상담방의 새 제목")
public class ChangeChatRoomTitleRequest {
    @NotBlank
    @Size(max = 510)
    @Schema(description = "공백이 아닌 제목. 앞뒤 공백 제거 후 Unicode 최대 255자. 서버가 코드포인트 수를 추가 검사합니다.", example = "보증금 반환 상담", requiredMode = Schema.RequiredMode.REQUIRED)
    private String title;
}
