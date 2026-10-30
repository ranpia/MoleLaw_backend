package com.MoleLaw_backend.chat.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Request-local display formatter. Raw text is separately retained for strict final validation. */
public final class StreamCitationDisplay {
    private final Map<String, Integer> numbers = new LinkedHashMap<>();
    private final StringBuilder citation = new StringBuilder();
    private boolean bracket;
    private boolean inside;
    private boolean closing;
    private boolean overflow;

    public String label(String id) {
        return "[근거 " + numbers.computeIfAbsent(id, ignored -> numbers.size() + 1) + "]";
    }

    public String append(String delta) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < delta.length(); i++) {
            char ch = delta.charAt(i);
            if (inside) {
                if (closing && ch == ']') {
                    String id = citation.toString();
                    if (!overflow && numbers.containsKey(id)) out.append(label(id));
                    inside = false;
                    closing = false;
                    overflow = false;
                    citation.setLength(0);
                } else {
                    if (closing) add(']');
                    closing = ch == ']';
                    if (!closing) add(ch);
                }
            } else if (bracket) {
                bracket = false;
                if (ch == '[') inside = true;
                else out.append('[').append(ch);
            } else if (ch == '[') bracket = true;
            else out.append(ch);
        }
        return out.toString();
    }

    private void add(char ch) {
        if (citation.length() < 128) citation.append(ch);
        else overflow = true;
    }

    public String finish() {
        String tail = bracket && !inside ? "[" : "";
        bracket = false;
        return tail;
    }
}
