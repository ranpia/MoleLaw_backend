package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.function.ToIntFunction;

/** A prompt-only excerpt; callers retain the original evidence for source validation and persistence. */
public final class CaseEvidenceExcerpt {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NOTICE = "[판결문 일부 발췌: 생략된 부분이 있으며 전체 판결문을 확인한 것이 아닙니다.]\n";
    private static final List<String> FIELDS = List.of("사건번호", "사건명", "법원명", "선고일자", "주문", "판시사항", "판결요지", "참조조문", "판례내용");

    private CaseEvidenceExcerpt() { }

    public static EvidenceItem fit(EvidenceItem original, int budget, List<String> targets, ToIntFunction<String> tokens) {
        if (budget <= 0) return null;
        if (tokens.applyAsInt(original.content()) <= budget) return original;
        String body = judgmentText(original.content());
        String best = null;
        int low = 1;
        int high = body.length();
        while (low <= high) {
            int chars = low + (high - low) / 2;
            String snippet = CaseJudgmentExcerpt.create(body, chars, targets);
            String excerpt = NOTICE + snippet;
            if (tokens.applyAsInt(excerpt) <= budget) {
                if (!snippet.isBlank()) best = excerpt;
                low = chars + 1;
            } else high = chars - 1;
        }
        if (best == null) return null;
        return new EvidenceItem(original.id(), original.kind(), original.sourceId(), original.title(),
                original.sourceUrl(), best, original.provenance());
    }

    private static String judgmentText(String raw) {
        try {
            var source = MAPPER.readTree(raw);
            if (!source.isObject()) return raw;
            var text = new StringBuilder();
            for (String field : FIELDS) {
                String value = source.path(field).asText("");
                if (!value.isBlank()) text.append(field).append(": ").append(value).append('\n');
            }
            return text.isEmpty() ? raw : text.toString();
        } catch (java.io.IOException invalidJson) {
            return raw;
        }
    }
}
