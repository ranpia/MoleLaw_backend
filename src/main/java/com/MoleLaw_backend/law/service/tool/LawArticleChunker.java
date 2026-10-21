package com.MoleLaw_backend.law.service.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Preserves article boundaries while splitting embedding input by Unicode code points.
 * A source is bounded to 2 MiB of UTF-8 JSON and 8192 resulting chunks.
 */
public final class LawArticleChunker {
    private static final int MAX_SOURCE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_CHUNKS = 8192;
    private final ObjectMapper mapper;

    public LawArticleChunker(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public List<Chunk> chunk(String rawJson, String title, int size, int overlap) {
        if (size <= 0 || size > 2000 || overlap < 0 || overlap >= size
                || rawJson == null || rawJson.length() > MAX_SOURCE_BYTES
                || rawJson.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES
                || title == null || title.isBlank()) {
            throw new IllegalArgumentException("Invalid law chunk input");
        }
        JsonNode root;
        try {
            root = mapper.readTree(rawJson);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Invalid law JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Invalid law JSON");
        }
        JsonNode articles = root.path("조문").path("조문단위");
        if (!articles.isObject() && !articles.isArray()) {
            throw new IllegalArgumentException("Missing law articles");
        }
        List<Chunk> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Iterable<JsonNode> entries = articles.isArray() ? articles : List.of(articles);
        for (JsonNode article : entries) {
            if (!article.isObject()) {
                throw new IllegalArgumentException("Invalid law article");
            }
            if ("전문".equals(article.path("조문여부").asText())) {
                continue;
            }
            String number = article.path("조문번호").asText("");
            String branch = article.path("조문가지번호").asText("");
            if (!number.matches("[0-9]{1,8}") || (!branch.isEmpty() && !branch.matches("[0-9]{1,8}"))) {
                throw new IllegalArgumentException("Invalid article number");
            }
            boolean branched = !branch.isEmpty() && !branch.matches("0+");
            String unitId = number + (branched ? "-" + branch : "");
            if (!ids.add(unitId)) {
                throw new IllegalArgumentException("Duplicate law article");
            }
            String articleTitle = "제" + number + "조" + (branched ? "의" + branch : "");
            String subtitle = normalize(article.path("조문제목").asText(""));
            if (!subtitle.isEmpty()) {
                articleTitle += "(" + subtitle + ")";
            }
            List<String> parts = new ArrayList<>();
            appendText(article.path("조문내용"), parts);
            appendUnits(article.path("항"), "항", parts);
            // Some provider responses omit the paragraph container.
            appendUnits(article.path("호"), "호", parts);
            appendUnits(article.path("목"), "목", parts);
            String articleText = normalize(String.join(" ", parts));
            int[] points = articleText.codePoints().toArray();
            if (points.length == 0 || points.length > 100_000) {
                throw new IllegalArgumentException("Invalid article text size");
            }
            int index = 0;
            for (int start = 0; start < points.length; start += size - overlap) {
                int count = Math.min(size, points.length - start);
                result.add(new Chunk(unitId, normalize(title) + " " + articleTitle,
                        new String(points, start, count), articleText, index++));
                if (result.size() > MAX_CHUNKS) {
                    throw new IllegalArgumentException("Too many law chunks");
                }
                if (start + count == points.length) {
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No law article content");
        }
        return List.copyOf(result);
    }

    private static void appendUnits(JsonNode node, String level, List<String> parts) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(entry -> appendUnits(entry, level, parts));
            return;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("Invalid article subdivision");
        }
        String number = normalize(node.path(level + "번호").asText(""));
        List<String> content = new ArrayList<>();
        appendText(node.path(level + "내용"), content);
        String text = normalize(String.join(" ", content));
        if (!number.isEmpty() && !text.startsWith(number)) {
            text = number + " " + text;
        }
        addPart(text, parts);
        if ("항".equals(level)) {
            appendUnits(node.path("호"), "호", parts);
            appendUnits(node.path("목"), "목", parts);
        } else if ("호".equals(level)) {
            appendUnits(node.path("목"), "목", parts);
        }
    }

    private static void appendText(JsonNode node, List<String> parts) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(entry -> appendText(entry, parts));
        } else if (node.isTextual()) {
            addPart(normalize(node.textValue()), parts);
        } else {
            throw new IllegalArgumentException("Invalid article content");
        }
    }

    private static void addPart(String text, List<String> parts) {
        if (!text.isEmpty() && (parts.isEmpty() || !parts.get(parts.size() - 1).equals(text))) {
            parts.add(text);
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("(?U)\\s+", " ").strip();
    }

    public record Chunk(String unitId, String title, String text, String articleText, int chunkIndex) {}
}
