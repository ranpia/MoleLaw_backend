package com.MoleLaw_backend.chat.util;

import java.util.Map;
import java.util.LinkedHashMap;

/** Replaces internal execution codes, including codes split across streamed chunks. */
public final class UserFacingAnswerText {
    private static final Map<String, String> CODES = new LinkedHashMap<>();
    static {
        CODES.put("NO_RELEVANT_CASES", "검토한 후보에서 쟁점에 적합한 판례를 확보하지 못한 상태");
        CODES.put("NO_READABLE_BODY", "검토할 판결 본문을 확보하지 못한 상태");
        CODES.put("BODY_BUDGET_EXCEEDED", "판결 본문 검토 한도를 넘은 상태");
        CODES.put("BUDGET_EXHAUSTED", "검색·검토 한도를 소진한 상태");
        CODES.put("PARTIAL_AVAILABLE", "일부 검색 경로의 결과만 확보한 상태");
        CODES.put("UNAVAILABLE", "검색 또는 검토 서비스를 이용하지 못한 상태");
        CODES.put("EMPTY", "해당 검색 조건에서 후보를 찾지 못한 상태");
        CODES.put("AVAILABLE", "판례 후보를 확보한 상태");
        CODES.put("DUPLICATE", "이미 검토한 검색 조건");
    }
    private final StringBuilder pending = new StringBuilder();
    public String append(String text) {
        pending.append(text);
        StringBuilder shown = new StringBuilder();
        String current = pending.toString();
        int position = 0;
        while (position < current.length()) {
            String match = null;
            for (String code : CODES.keySet()) if (current.startsWith(code, position)) { match = code; break; }
            if (match != null) { shown.append(CODES.get(match)); position += match.length(); }
            else {
                boolean prefix = false;
                int remaining = current.length() - position;
                for (String code : CODES.keySet()) if (remaining < code.length() && code.regionMatches(0, current, position, remaining)) { prefix = true; break; }
                if (prefix) break;
                shown.append(current.charAt(position++));
            }
        }
        pending.setLength(0);
        pending.append(current, position, current.length());
        return shown.toString();
    }
    public String finish() { String tail = pending.toString(); pending.setLength(0); return tail; }
    public static String normalize(String text) {
        for (var entry : CODES.entrySet()) text = text.replace(entry.getKey(), entry.getValue());
        return text;
    }
}
