package com.MoleLaw_backend.chat.service;

import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.chat.util.EncryptUtil;
import java.nio.charset.StandardCharsets;

/** UTF-8 byte limits apply to plaintext before model calls and encryption. */
public final class ChatContentLimits {
    public static final int MAX_QUESTION_BYTES = 32 * 1024;
    public static final int MAX_QUESTION_CODE_POINTS = 2000;
    private ChatContentLimits() { }

    public static void question(String question) {
        require(question, MAX_QUESTION_BYTES, ErrorCode.INVALID_REQUEST);
        if (question.codePointCount(0, question.length()) > MAX_QUESTION_CODE_POINTS) {
            throw new MolelawException(ErrorCode.INVALID_REQUEST, "질문은 Unicode 문자 2000자 이하여야 합니다");
        }
    }

    public static void answer(String answer) {
        require(answer, EncryptUtil.MAX_PLAINTEXT_BYTES, ErrorCode.GPT_API_FAILURE);
    }

    public static void title(String title) {
        if (title == null || title.isBlank() || title.codePointCount(0, title.length()) > 255) {
            throw new MolelawException(ErrorCode.GPT_API_FAILURE, "상담방 제목은 1–255자여야 합니다");
        }
    }

    private static void require(String text, int maxBytes, ErrorCode code) {
        if (text == null || text.isBlank() || text.getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw new MolelawException(code, "메시지가 비어 있거나 UTF-8 크기 한도를 초과했습니다");
        }
    }
}
