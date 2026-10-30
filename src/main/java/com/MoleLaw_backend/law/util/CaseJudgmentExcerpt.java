package com.MoleLaw_backend.law.util;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Bounded text for LLM review; the immutable source retains the full judgment. */
public final class CaseJudgmentExcerpt {
    private CaseJudgmentExcerpt() { }
    public static String create(String body, int limit, List<String> targets) {
        if (body.length() <= limit) return body;
        int head = limit / 2;
        StringBuilder result = new StringBuilder(part(body, 0, head));
        String separator = "\n[중간 생략·일부 발췌: 전체 판결 확인 필요]\n";
        int windows = Math.max(1, targets.size());
        for (String target : targets) {
            String pattern = target.replaceAll("\\s+", "").codePoints().mapToObj(cp -> Pattern.quote(new String(Character.toChars(cp))))
                    .collect(Collectors.joining("\\s*"));
            var matches = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE).matcher(body);
            int space = Math.min((limit - head) / windows, limit - result.length() - separator.length());
            if (space <= 0 || !matches.find(head)) continue;
            int start = Math.max(head, matches.start() - space / 3);
            int end = Math.min(body.length(), start + space);
            result.append(separator).append(part(body, start, end));
        }
        int remaining = limit - result.length() - separator.length();
        if (remaining > 0) result.append(separator).append(part(body, body.length() - remaining, body.length()));
        return result.toString();
    }

    private static String part(String text, int start, int end) {
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(start, Math.max(start, end));
    }
}
