package com.MoleLaw_backend.auth.security;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Test-only state machine; real Redis Lua behavior has separate integration coverage. */
public class InMemoryRefreshTokenStore implements RefreshTokenStore {
    private final Map<Long, Long> generations = new HashMap<>();
    private final Map<String, Family> families = new HashMap<>();
    private long nextGeneration;

    @Override
    public synchronized long generation(Long userId) {
        return generations.computeIfAbsent(userId, unused -> ++nextGeneration);
    }

    @Override
    public synchronized void create(Long userId, String familyId, String hash, Instant expiresAt, long generation) {
        if (!Long.valueOf(generation).equals(generations.get(userId))) throw invalid();
        families.put(userId + ":" + familyId, new Family(hash, expiresAt, generation));
    }

    @Override
    public synchronized Instant rotate(Long userId, String familyId, String previousHash, String nextHash) {
        Family family = families.get(userId + ":" + familyId);
        if (family == null || family.revoked || !family.expiry.isAfter(Instant.now())
                || !Long.valueOf(family.generation).equals(generations.get(userId))
                || !family.seen.contains(previousHash)) throw invalid();
        if (!family.current.equals(previousHash)) {
            family.revoked = true;
            throw new MolelawException(ErrorCode.REFRESH_TOKEN_REUSED);
        }
        family.current = nextHash;
        family.seen.add(nextHash);
        return family.expiry;
    }

    @Override
    public synchronized void revoke(Long userId, String familyId, String knownHash) {
        Family family = families.get(userId + ":" + familyId);
        if (family != null && family.seen.contains(knownHash)) family.revoked = true;
    }

    @Override
    public synchronized void revokeAll(Long userId) {
        generations.put(userId, ++nextGeneration);
    }

    private MolelawException invalid() { return new MolelawException(ErrorCode.INVALID_REFRESH_TOKEN); }

    private static class Family {
        String current;
        final Instant expiry;
        final long generation;
        final Set<String> seen = new HashSet<>();
        boolean revoked;
        Family(String hash, Instant expiry, long generation) {
            this.current = hash;
            this.expiry = expiry;
            this.generation = generation;
            seen.add(hash);
        }
    }
}
