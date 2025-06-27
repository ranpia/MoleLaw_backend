package com.MoleLaw_backend.controller;

import com.MoleLaw_backend.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 관련 API")
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Operation(summary = "로그아웃", description = "JWT 기반 로그아웃 (클라이언트가 토큰 삭제하면 인증 종료)")
    @PostMapping(value = "/logout", consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ApiResponse<String>> logout() {
        System.out.println("✅ 로그아웃 API 호출됨");
        return ResponseEntity.ok(ApiResponse.success("로그아웃 되었습니다."));
    }
}
