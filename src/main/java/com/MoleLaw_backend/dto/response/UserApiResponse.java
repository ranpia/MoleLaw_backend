package com.MoleLaw_backend.dto.response;

import com.MoleLaw_backend.dto.response.ApiResponse;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(name = "AuthUserEnvelope")
public class UserApiResponse extends ApiResponse<UserResponse> { }