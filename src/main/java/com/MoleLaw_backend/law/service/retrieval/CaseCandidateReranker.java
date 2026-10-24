package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.law.config.CaseSearchProperties;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.stream.Collectors;

/** Ranks actual judgment excerpts; list metadata alone never authorizes a citation. */
@Service
public class CaseCandidateReranker {
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final CaseSearchProperties properties;

    public CaseCandidateReranker(@Qualifier("caseRerankChatClient") ChatClient client, ObjectMapper mapper,
            CaseSearchProperties properties) {
        this.client = client; this.mapper = mapper; this.properties = properties;
    }

    public List<LawCandidateSelection.Ranking> rank(String query, EvidenceItem law, List<EvidenceItem> cases) {
        if (cases.isEmpty()) return List.of();
        try {
            var input = mapper.createObjectNode().put("issueQuery", query).put("referenceLaw", law.title())
                    .put("minimumScore", properties.getMinimumScore());
            input.put("lawExcerpt", excerpt(law.content()));
            var docs = input.putArray("judgments");
            for (var item : cases) docs.addObject().put("candidateId", item.sourceId()).put("title", item.title())
                    .put("bodyExcerpt", excerpt(judgmentText(item.content())));
            String output = client.prompt().user(mapper.writeValueAsString(input)).call().content();
            if (output == null || output.length() > 16384) throw new IllegalArgumentException("Invalid rerank output");
            var result = mapper.readerFor(LawCandidateSelection.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT).<LawCandidateSelection>readValue(output);
            var supplied = cases.stream().map(EvidenceItem::sourceId).collect(Collectors.toSet());
            if (result == null || result.rankings().size() != supplied.size()
                    || !supplied.equals(result.rankings().stream().map(LawCandidateSelection.Ranking::candidateId).collect(Collectors.toSet())))
                throw new IllegalArgumentException("Unknown judgment ID");
            return result.rankings();
        } catch (Exception invalid) {
            throw new com.MoleLaw_backend.common.ai.exception.GptApiException(
                    com.MoleLaw_backend.common.exception.ErrorCode.GPT_API_FAILURE);
        }
    }

    private String excerpt(String body) {
        int limit = properties.getExcerptChars();
        if (body.length() <= limit) return body;
        int half = limit / 2;
        return body.substring(0, half) + "\n[중간 생략: 전체 판결 확인 필요]\n" + body.substring(body.length() - half);
    }

    private String judgmentText(String raw) throws java.io.IOException {
        if (!raw.stripLeading().startsWith("{")) return raw;
        var judgment = mapper.readTree(raw);
        String text = "판시사항: " + judgment.path("판시사항").asText() + "\n판결요지: " + judgment.path("판결요지").asText()
                + "\n참조조문: " + judgment.path("참조조문").asText() + "\n판결본문: " + judgment.path("판례내용").asText();
        return text.replaceAll("<[^>]{0,1000}>", " ");
    }
}
