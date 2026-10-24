package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceReading;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.service.tool.EvidenceReadToolService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Two bounded catalog-selection rounds, with local full-article reads and no new collection or embeddings.
 */
@Slf4j
@Service
public class EvidenceReadPlanner {
    private final ChatClient client;
    private final ObjectMapper mapper;
    private final EvidenceReadToolService reader;
    private final int catalogLimit;
    private final int bodyLimit;
    private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);

    public EvidenceReadPlanner(@Qualifier("evidenceReadChatClient") ChatClient client, ObjectMapper mapper,
                               EvidenceReadToolService reader, @Value("${app.ai.evidence.catalog-max-input-tokens:4000}") int catalogLimit,
                               @Value("${app.ai.evidence.body-max-input-tokens:12000}") int bodyLimit) {
        if (catalogLimit < 128 || catalogLimit > 16000 || bodyLimit < 128 || bodyLimit > 64000)
            throw new IllegalArgumentException("Invalid evidence read budgets");
        this.client = client;
        this.mapper = mapper;
        this.reader = reader;
        this.catalogLimit = catalogLimit;
        this.bodyLimit = bodyLimit;
    }

    public EvidenceReading read(String question, List<EvidenceItem> candidates, QuestionRetrievalPlan plan) {
        var catalog = mapper.createArrayNode();
        if (candidates.size() > 15 || candidates.stream().map(EvidenceItem::id).distinct().count() != candidates.size())
            throw new IllegalArgumentException("Invalid evidence catalog");
        for (var item : candidates) {
            var entry = catalog.addObject().put("id", item.id()).put("kind", item.kind()).put("title", item.title())
                    .put("estimatedBodyTokens", tokens.estimate(mapper.valueToTree(item).toString()));
            if (item.provenance() != null) entry.put("lawId", item.provenance().lawId())
                    .put("articlePath", item.provenance().articlePath()).put("effectiveDate", item.provenance().effectiveDate());
        }
        if (candidates.isEmpty()) return new EvidenceReading(catalog, List.of(), List.of());
        var read = new ArrayList<EvidenceItem>();
        var outcomes = new ArrayList<EvidenceReading.Outcome>();
        var attempted = new HashSet<String>();
        var suppliedIds = candidates.stream().map(EvidenceItem::id).collect(java.util.stream.Collectors.toSet());
        int used = 0;
        String stage = "CATALOG_INPUT";
        String contractIssue = "NONE";
        try {
            for (int round = 0; round < 2 && attempted.size() < candidates.size(); round++) {
                var input = mapper.createObjectNode().put("question", question).put("remainingBodyTokens", bodyLimit - used);
                input.set("retrievalPlan", mapper.valueToTree(plan));
                var remaining = catalog.deepCopy();
                for (int i = remaining.size() - 1; i >= 0; i--)
                    if (attempted.contains(remaining.get(i).path("id").asText())) remaining.remove(i);
                input.set("candidateCatalog", remaining);
                input.set("readOutcomes", mapper.valueToTree(outcomes));
                if (tokens.estimate(input.toString()) > catalogLimit) {
                    if (round == 0) throw new MolelawException(ErrorCode.EVIDENCE_INSUFFICIENT);
                    break; // Keep already-read evidence without exceeding the second catalog call's budget.
                }
                stage = "MODEL_SELECTION";
                var availableIds = new ArrayList<String>();
                remaining.forEach(item -> availableIds.add(item.path("id").asText()));
                var schema = Map.<String, Object>of("type", "object", "additionalProperties", false,
                        "required", List.of("evidenceIds"), "properties", Map.of("evidenceIds",
                                Map.of("type", "array", "maxItems", Math.min(8, availableIds.size()),
                                        "items", Map.of("type", "string", "enum", availableIds))));
                var format = ResponseFormat.builder().type(ResponseFormat.Type.JSON_SCHEMA)
                        .jsonSchema(ResponseFormat.JsonSchema.builder().name("evidence_read_selection")
                                .strict(true).schema(schema).build()).build();
                String output = client.prompt().user(input.toString())
                        .options(OpenAiChatOptions.builder().responseFormat(format).build()).call().content();
                if (output == null || output.length() > 4096)
                    throw new IllegalArgumentException("Invalid evidence read selection");
                stage = "SELECTION_CONTRACT";
                contractIssue = "INVALID_STRUCTURE";
                EvidenceReading.Selection selection = mapper.readerFor(EvidenceReading.Selection.class)
                        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                        .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                        .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(output);
                if (selection == null) {
                    contractIssue = "NULL_SELECTION";
                    throw new IllegalArgumentException("Null evidence reading selection");
                }
                if (!suppliedIds.containsAll(selection.evidenceIds())) {
                    contractIssue = "UNKNOWN_ID";
                    throw new IllegalArgumentException("Unknown evidence read ID");
                }
                contractIssue = "NONE";
                var newIds = selection.evidenceIds().stream().filter(id -> !attempted.contains(id)).toList();
                if (newIds.isEmpty()) break;
                stage = "SOURCE_READ";
                for (var item : reader.readEvidence(candidates, newIds)) {
                    attempted.add(item.id());
                    int cost = tokens.estimate(mapper.valueToTree(item).toString());
                    if (cost > bodyLimit - used) {
                        outcomes.add(new EvidenceReading.Outcome(item.id(), EvidenceReading.Status.BODY_BUDGET_EXCEEDED, cost));
                    } else {
                        used += cost;
                        read.add(item);
                        outcomes.add(new EvidenceReading.Outcome(item.id(), EvidenceReading.Status.READ, cost));
                    }
                }
            }
            log.info("Evidence reading candidateCount={} readCount={} deferredCount={} bodyTokens={} bodyLimit={}",
                    candidates.size(), read.size(), outcomes.size() - read.size(), used, bodyLimit);
            return new EvidenceReading(catalog, read, outcomes);
        } catch (MolelawException failure) {
            throw failure;
        } catch (Exception failure) {
            log.warn("Evidence reading failed stage={} contractIssue={} errorType={} candidateCount={} readCount={}",
                    stage, contractIssue, failure.getClass().getSimpleName(), candidates.size(), read.size());
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
