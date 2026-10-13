package com.MoleLaw_backend.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import com.MoleLaw_backend.exception.ErrorResponse;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {

    @Schema(description = "요청 처리 성공 여부", example = "true")
    private boolean success;
    @Schema(description = "성공 응답 데이터. null이면 JSON에서 생략됩니다.")
    private T data;
    @Schema(description = "실패 응답 오류. null이면 JSON에서 생략됩니다.")
    private ErrorResponse error;

    // ✅ 성공 응답
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, data, null);
    }

    // ✅ ErrorResponse 객체를 직접 넘기는 경우
    public static <T> ApiResponse<T> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }

    // ✅ 문자열 메시지 하나만 넘겨도 처리 가능 (String → ErrorResponse로 변환됨)
    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, new ErrorResponse(message));
    }
}
