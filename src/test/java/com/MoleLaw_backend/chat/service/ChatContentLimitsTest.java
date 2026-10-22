package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.common.exception.MolelawException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ChatContentLimitsTest {
    @Test
    void appliesUnicodeCodePointQuestionAndTitleBoundaries() {
        assertThatCode(() -> ChatContentLimits.question("😀".repeat(2000))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChatContentLimits.question("가".repeat(2001))).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> ChatContentLimits.question(null)).isInstanceOf(MolelawException.class);
        assertThatCode(() -> ChatContentLimits.title("😀".repeat(255))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChatContentLimits.title("가".repeat(256))).isInstanceOf(MolelawException.class);
    }

    @Test
    void boundsAnswerPlaintextBeforeEncryption() {
        assertThatCode(() -> ChatContentLimits.answer("x".repeat(1024 * 1024))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChatContentLimits.answer("x".repeat(1024 * 1024 + 1))).isInstanceOf(MolelawException.class);
        assertThatThrownBy(() -> ChatContentLimits.answer(" ")).isInstanceOf(MolelawException.class);
    }
}
