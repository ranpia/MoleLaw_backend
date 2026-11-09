package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;

/** Concrete generic schemas for documented authentication responses. */
public final class AuthApiSchemas {
    private AuthApiSchemas() { }
    @Schema(name = "AuthTokenEnvelope")
    public static class AuthEnvelope extends ApiResponse<AuthResponse> { }
    @Schema(name = "AuthStringEnvelope")
    public static class StringEnvelope extends ApiResponse<String> { }
    @Schema(name = "AuthErrorEnvelope")
    public static class ErrorEnvelope extends ApiResponse<Void> { }
}
