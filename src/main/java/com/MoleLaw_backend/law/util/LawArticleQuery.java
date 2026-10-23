package com.MoleLaw_backend.law.util;

import java.util.List;
import java.util.regex.Pattern;

/** Exact article lookup is scoped only by a complete, server-observed law title prefix. */
public final class LawArticleQuery {
    private static final Pattern ARTICLE = Pattern.compile("제\\s*([0-9]{1,8})\\s*조(?:\\s*의\\s*([0-9]{1,8}))?");
    private LawArticleQuery() { }

    public static List<String> paths(String query, String lawTitle) {
        if (lawTitle == null || lawTitle.isBlank()) return List.of();
        String text = query.strip();
        if (!text.startsWith(lawTitle)) return List.of();
        String suffix = text.substring(lawTitle.length());
        if (suffix.startsWith(" 시행령") || suffix.startsWith(" 시행규칙")) return List.of();
        if (!suffix.isEmpty() && !Character.isWhitespace(suffix.charAt(0)) && !suffix.startsWith("제")) return List.of();
        var matcher = ARTICLE.matcher(suffix);
        var paths = new java.util.LinkedHashSet<String>();
        while (matcher.find() && paths.size() < 15) {
            String path = new java.math.BigInteger(matcher.group(1)).toString();
            if (matcher.group(2) != null) path += "-" + new java.math.BigInteger(matcher.group(2));
            paths.add(path);
        }
        return List.copyOf(paths);
    }
}
