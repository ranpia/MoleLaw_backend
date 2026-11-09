package com.MoleLaw_backend.dto.security;

import java.time.Instant;

public record AccessTokenIdentity(String tokenId, String subject, Instant expiresAt) { }
