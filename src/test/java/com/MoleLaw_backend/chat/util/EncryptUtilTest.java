package com.MoleLaw_backend.chat.util;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class EncryptUtilTest {
    @Test
    void usesRandomNonceAndAuthenticatesUnicodeContent() {
        String text = "상담 내용 😀 {literal}";
        String first = EncryptUtil.encrypt(text);
        String second = EncryptUtil.encrypt(text);
        assertThat(first).startsWith("gcm1.").isNotEqualTo(second).doesNotContain(text);
        assertThat(EncryptUtil.decrypt(first)).isEqualTo(text);
        assertThat(EncryptUtil.decrypt(second)).isEqualTo(text);
    }

    @Test
    void rejectsTamperedCiphertextNonceAndKeyVersion() {
        String[] envelope = EncryptUtil.encrypt("민감한 대화").split("\\.");
        byte[] ciphertext = Base64.getDecoder().decode(envelope[3]);
        ciphertext[0] ^= 1;
        assertThatThrownBy(() -> EncryptUtil.decrypt(envelope[0] + "." + envelope[1] + "." + envelope[2]
                + "." + Base64.getEncoder().encodeToString(ciphertext))).isInstanceOf(IllegalStateException.class);
        byte[] nonce = Base64.getDecoder().decode(envelope[2]);
        nonce[0] ^= 1;
        assertThatThrownBy(() -> EncryptUtil.decrypt(envelope[0] + "." + envelope[1] + "."
                + Base64.getEncoder().encodeToString(nonce) + "." + envelope[3]))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> EncryptUtil.decrypt(envelope[0] + ".wrong-version." + envelope[2] + "." + envelope[3]))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptUtil.decrypt("legacy-ciphertext"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void supportsLongtextSizedMessagesAndEnforcesExactPlaintextByteLimit() {
        String large = "한".repeat(EncryptUtil.MAX_PLAINTEXT_BYTES / 3) + "x";
        assertThat(large.getBytes(StandardCharsets.UTF_8)).hasSize(EncryptUtil.MAX_PLAINTEXT_BYTES);
        String encrypted = EncryptUtil.encrypt(large);
        assertThat(encrypted.length()).isGreaterThan(65535);
        assertThat(EncryptUtil.decrypt(encrypted)).isEqualTo(large);
        assertThatThrownBy(() -> EncryptUtil.encrypt(large + "x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptUtil.encrypt(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EncryptUtil.encrypt(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
