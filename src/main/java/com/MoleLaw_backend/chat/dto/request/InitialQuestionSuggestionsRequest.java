package com.MoleLaw_backend.chat.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "최초 상담 전 추천 질문을 만들기 위한 사용자 초안")
public record InitialQuestionSuggestionsRequest(
        @NotBlank @Size(max = 4000)
        @Schema(description = "사용자가 작성한 질문 원문. Unicode 최대 2000자·UTF-8 최대 32KiB. 빈 입력은 허용하지 않습니다.", example = "시공 하자와 관리 소홀이 함께 사고를 일으킨 경우 책임이 궁금합니다.", requiredMode = Schema.RequiredMode.REQUIRED)
        String query) { }
