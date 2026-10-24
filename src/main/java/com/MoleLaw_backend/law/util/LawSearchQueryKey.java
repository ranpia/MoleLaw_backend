package com.MoleLaw_backend.law.util;

import java.text.Normalizer;
import java.util.Locale;

/** Dedup identity only; the original query and explicit filters are sent unchanged. */
public final class LawSearchQueryKey {
    private LawSearchQueryKey() { }
    public static String of(String query) {
        String normalized = Normalizer.normalize(query, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("[\\p{Z}\\s\\p{P}]+", "");
        return normalized.isEmpty() ? query.strip() : normalized;
    }
}
