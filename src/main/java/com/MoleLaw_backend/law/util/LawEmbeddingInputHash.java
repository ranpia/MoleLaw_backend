package com.MoleLaw_backend.law.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** The exact title/newline/chunk input, independent of a source snapshot's UUID. */
public final class LawEmbeddingInputHash {
    private LawEmbeddingInputHash() { }

    public static String of(String title, String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((title + "\n" + text).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
