package com.MoleLaw_backend.chat.util;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/** AES-256-GCM envelope: format.keyVersion.base64(nonce).base64(ciphertext+tag). */
@Component
public final class EncryptUtil {
    public static final int MAX_PLAINTEXT_BYTES = 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String FORMAT = "gcm1";
    private static final int NONCE_BYTES = 12;

    public EncryptUtil() { key(); keyVersion(); }

    public static String encrypt(String plainText) {
        if (plainText == null || plainText.isBlank()) {
            throw new IllegalArgumentException("Message plaintext must not be blank");
        }
        byte[] plaintext = plainText.getBytes(StandardCharsets.UTF_8);
        if (plaintext.length > MAX_PLAINTEXT_BYTES) {
            throw new IllegalArgumentException("Message plaintext exceeds 1 MiB");
        }
        String version = keyVersion();
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, version, nonce);
            return FORMAT + "." + version + "." + Base64.getEncoder().encodeToString(nonce)
                    + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plaintext));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("Message encryption failed", ex);
        }
    }

    public static String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.length() > 4 * ((MAX_PLAINTEXT_BYTES + 18) / 3) + 128) {
            throw new IllegalArgumentException("Invalid message envelope");
        }
        String[] parts = encryptedText.split("\\.", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0]) || !keyVersion().equals(parts[1])) {
            throw new IllegalArgumentException("Unsupported message encryption format or key version");
        }
        try {
            byte[] nonce = Base64.getDecoder().decode(parts[2]);
            byte[] ciphertext = Base64.getDecoder().decode(parts[3]);
            if (nonce.length != NONCE_BYTES || ciphertext.length < 16
                    || ciphertext.length > MAX_PLAINTEXT_BYTES + 16) {
                throw new IllegalArgumentException("Invalid message envelope");
            }
            return new String(cipher(Cipher.DECRYPT_MODE, parts[1], nonce).doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException ex) {
            throw new IllegalStateException("Message decryption failed", ex);
        }
    }

    private static Cipher cipher(int mode, String version, byte[] nonce) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key(), new GCMParameterSpec(128, nonce));
        cipher.updateAAD((FORMAT + "." + version).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static SecretKeySpec key() {
        String encoded = System.getenv("CHAT_ENCRYPTION_KEY");
        try {
            byte[] decoded = encoded == null ? new byte[0] : Base64.getDecoder().decode(encoded);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(decoded, "AES");
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("CHAT_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
    }

    private static String keyVersion() {
        String value = System.getenv("CHAT_ENCRYPTION_KEY_VERSION");
        String version = value == null ? "v1" : value;
        if (!version.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalStateException("Invalid CHAT_ENCRYPTION_KEY_VERSION");
        }
        return version;
    }
}
