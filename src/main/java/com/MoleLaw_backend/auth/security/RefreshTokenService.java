package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern FORMAT = Pattern.compile("r1\\.([1-9][0-9]{0,18})\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.([A-Za-z0-9_-]{43})");
    private final RefreshTokenStore store;

    public Grant issue(Long userId) {
        JwtUtil.parseUserId(userId == null ? null : userId.toString());
        long generation = store.generation(userId);
        String familyId = UUID.randomUUID().toString();
        String value = token(userId, familyId);
        Instant expiry = Instant.now().plus(Duration.ofDays(7));
        store.create(userId, familyId, hash(value), expiry, generation);
        return new Grant(value, userId, expiry);
    }

    public Long userId(String value) {
        return parse(value).userId();
    }

    public Grant rotate(String value) {
        Descriptor previous = parse(value);
        String next = token(previous.userId(), previous.familyId());
        Instant expiry = store.rotate(previous.userId(), previous.familyId(), previous.hash(), hash(next));
        return new Grant(next, previous.userId(), expiry);
    }

    public void revoke(String value) {
        if (value == null) return;
        Descriptor descriptor;
        try {
            descriptor = parse(value);
        } catch (MolelawException e) {
            return; // A malformed/legacy cookie has no registered family to revoke.
        }
        store.revoke(descriptor.userId(), descriptor.familyId(), descriptor.hash());
    }

    public void revokeAll(Long userId) {
        store.revokeAll(userId);
    }

    private Descriptor parse(String value) {
        if (value == null || value.length() > 110) throw new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN);
        var match = FORMAT.matcher(value);
        if (!match.matches()) throw new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN);
        try {
            return new Descriptor(JwtUtil.parseUserId(match.group(1)), match.group(2), hash(value));
        } catch (IllegalArgumentException e) {
            throw new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private String token(Long userId, String familyId) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        return "r1." + userId + "." + familyId + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record Descriptor(Long userId, String familyId, String hash) {}

    public record Grant(String value, Long userId, Instant expiresAt) {
        @Override public String toString() { return "RefreshGrant[userId=" + userId + ", expiresAt=" + expiresAt + "]"; }
    }
}
