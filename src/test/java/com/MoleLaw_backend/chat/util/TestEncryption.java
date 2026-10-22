package com.MoleLaw_backend.chat.util;

/** Isolated synthetic key shared with the test profile; never a runtime default. */
public final class TestEncryption {
    public static final String KEY = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
    public static final String VERSION = "test-v1";
    private TestEncryption() { }
    public static EncryptUtil create() { return new EncryptUtil(KEY, VERSION); }
}