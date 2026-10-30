package com.MoleLaw_backend.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

public record CsrfResponse(
        @Schema(description = "상태 변경 요청의 X-XSRF-TOKEN 헤더에 넣을 토큰", example = "example-csrf-token") String token,
        @Schema(description = "CSRF 토큰을 전달할 헤더 이름", example = "X-XSRF-TOKEN") String headerName) { }