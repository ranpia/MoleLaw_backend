package com.MoleLaw_backend.chat.util;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class EncryptUtilTest {
    @Test
    void independentInjectedKeysDoNotShareGlobalState() {
        var first = TestEncryption.create();
        byte[] otherKey = new byte[32];
        java.util.Arrays.fill(otherKey, (byte) 7);
        var second = new EncryptUtil(Base64.getEncoder().encodeToString(otherKey), TestEncryption.VERSION);
        String encrypted = first.encrypt("기존 메시지");
        assertThatThrownBy(() -> second.decrypt(encrypted)).isInstanceOf(IllegalStateException.class);
        assertThat(first.decrypt(encrypted)).isEqualTo("기존 메시지");
        assertThat(second.decrypt(second.encrypt("다른 키"))).isEqualTo("다른 키");
    }

    @Test
    void rejectsMissingMalformedKeysAndInvalidVersionsAtConstruction() {
        for (String key : new String[]{null, "", "not-base64!", Base64.getEncoder().encodeToString(new byte[31]),
                Base64.getEncoder().encodeToString(new byte[33])}) {
            assertThatThrownBy(() -> new EncryptUtil(key, "v1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("CHAT_ENCRYPTION_KEY must be a Base64-encoded 32-byte key");
        }
        for (String version : new String[]{null, "", "v1.invalid", "x".repeat(33)}) {
            assertThatThrownBy(() -> new EncryptUtil(TestEncryption.KEY, version))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Invalid CHAT_ENCRYPTION_KEY_VERSION");
        }
    }

    @Test
    void usesRandomNonceAndAuthenticatesUnicodeContent() {
        String text = "상담 내용 😀 {literal}";
        String first = TestEncryption.create().encrypt(text);
        String second = TestEncryption.create().encrypt(text);
        assertThat(first).startsWith("gcm1.").isNotEqualTo(second).doesNotContain(text);
        assertThat(TestEncryption.create().decrypt(first)).isEqualTo(text);
        assertThat(TestEncryption.create().decrypt(second)).isEqualTo(text);
    }

    @Test
    void rejectsTamperedCiphertextNonceAndKeyVersion() {
        String[] envelope = TestEncryption.create().encrypt("민감한 대화").split("\\.");
        byte[] ciphertext = Base64.getDecoder().decode(envelope[3]);
        ciphertext[0] ^= 1;
        assertThatThrownBy(() -> TestEncryption.create().decrypt(envelope[0] + "." + envelope[1] + "." + envelope[2]
                + "." + Base64.getEncoder().encodeToString(ciphertext))).isInstanceOf(IllegalStateException.class);
        byte[] nonce = Base64.getDecoder().decode(envelope[2]);
        nonce[0] ^= 1;
        assertThatThrownBy(() -> TestEncryption.create().decrypt(envelope[0] + "." + envelope[1] + "."
                + Base64.getEncoder().encodeToString(nonce) + "." + envelope[3]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> TestEncryption.create().decrypt(envelope[0] + ".wrong-version." + envelope[2] + "." + envelope[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestEncryption.create().decrypt("legacy-ciphertext"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsLongtextSizedMessagesAndEnforcesExactPlaintextByteLimit() {
        String large = "한".repeat(EncryptUtil.MAX_PLAINTEXT_BYTES / 3) + "x";
        assertThat(large.getBytes(StandardCharsets.UTF_8)).hasSize(EncryptUtil.MAX_PLAINTEXT_BYTES);
        String encrypted = TestEncryption.create().encrypt(large);
        assertThat(encrypted.length()).isGreaterThan(65535);
        assertThat(TestEncryption.create().decrypt(encrypted)).isEqualTo(large);
        assertThatThrownBy(() -> TestEncryption.create().encrypt(large + "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestEncryption.create().encrypt(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestEncryption.create().encrypt(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
