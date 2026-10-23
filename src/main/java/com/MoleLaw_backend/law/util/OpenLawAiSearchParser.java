package com.MoleLaw_backend.law.util;

import com.MoleLaw_backend.law.dto.retrieval.AiLawArticle;
import com.MoleLaw_backend.law.dto.retrieval.AiLawArticlePage;
import com.MoleLaw_backend.law.dto.tool.AiLawSearchRequest;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Documented article fields only. Provider error/HTML/unknown shapes are never an empty result. */
public final class OpenLawAiSearchParser {
    private OpenLawAiSearchParser() { }

    public static AiLawArticlePage parse(JsonNode document, AiLawSearchRequest request) {
        if (document == null || !document.isObject()) throw invalid();
        JsonNode root = document.has("AiSearch") ? document.get("AiSearch")
                : document.has("aiSearch") ? document.get("aiSearch") : document;
        if (!root.isObject()) throw invalid();
        if (root.has("error") || root.has("오류") || root.has("target") && !"aiSearch".equals(root.path("target").asText())) throw invalid();
        JsonNode count = root.has("검색결과개수") ? root.get("검색결과개수") : root.path("totalCnt");
        if (!count.asText().matches("[0-9]{1,9}")) throw invalid();
        int total = count.asInt();
        if (root.has("page") && root.path("page").asInt(-1) != request.page()) throw invalid();
        JsonNode rows = root.has("law") ? root.get("law") : root.has("법령조문") ? root.get("법령조문") : root.path("조문");
        List<JsonNode> nodes = new ArrayList<>();
        if (rows.isArray()) rows.forEach(nodes::add);
        else if (rows.isObject() && !rows.isEmpty()) nodes.add(rows);
        else if (!(rows.isMissingNode() || rows.isNull() || rows.isObject())) throw invalid();
        long offset = (long) (request.page() - 1) * request.display();
        if (nodes.size() > request.display() || (total == 0 && !nodes.isEmpty())
                || (offset < total && nodes.isEmpty()) || offset + nodes.size() > total && !nodes.isEmpty()) throw invalid();
        List<AiLawArticle> articles = new ArrayList<>();
        var seen = new HashSet<String>();
        for (JsonNode node : nodes) {
            String articleId = id(node, "법령조문ID"), mst = id(node, "법령일련번호");
            String date = optional(node, "시행일자");
            if (date != null) {
                try { LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE); }
                catch (RuntimeException invalidDate) { throw invalid(); }
            }
            String body = text(node, "조문내용", 128 * 1024);
            if (body.getBytes(StandardCharsets.UTF_8).length > 128 * 1024 || !seen.add(mst + ":" + articleId)) throw invalid();
            String number = number(node, "조문번호");
            String branch = node.hasNonNull("조문가지번호") && !node.path("조문가지번호").asText().isBlank()
                    ? number(node, "조문가지번호") : null;
            if ("0".equals(branch)) branch = null;
            articles.add(new AiLawArticle(articleId, id(node, "법령ID"), mst,
                    text(node, "법령명", 1000), date, number, branch,
                    optional(node, "조문제목"), body));
        }
        return new AiLawArticlePage(total, offset + nodes.size() < total, articles);
    }

    private static String id(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!value.matches("[0-9]{1,20}")) throw invalid();
        return value;
    }
    private static String number(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (!value.matches("[0-9]{1,8}")) throw invalid();
        return new java.math.BigInteger(value).toString();
    }
    private static String text(JsonNode node, String field, int limit) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > limit) throw invalid();
        return value.asText();
    }
    private static String optional(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull() || value.isTextual() && value.asText().isBlank()) return null;
        if (!value.isTextual() || value.asText().length() > 1000) throw invalid();
        return value.asText();
    }
    private static LegalToolException invalid() {
        return new LegalToolException(LegalToolException.Reason.INVALID_RESPONSE, false);
    }
}
