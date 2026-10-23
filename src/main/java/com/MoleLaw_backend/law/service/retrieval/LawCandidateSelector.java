package com.MoleLaw_backend.law.service.retrieval;

import com.MoleLaw_backend.common.ai.exception.GptApiException;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.law.dto.retrieval.LawCandidateSelection;
import com.MoleLaw_backend.law.dto.retrieval.QuestionRetrievalPlan;
import com.MoleLaw_backend.law.dto.retrieval.EvidenceDecision;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts.Candidate;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class LawCandidateSelector {
    private final ChatClient client;
    private final ObjectMapper mapper;

    public LawCandidateSelector(@Qualifier("lawCandidateChatClient") ChatClient client, ObjectMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public List<Candidate> select(String question, QuestionRetrievalPlan plan,
                                  EvidenceDecision.LawSearch condition, List<Candidate> candidates) {
        var rankings = rank(question, plan, condition, candidates);
        return rankings.stream().filter(item -> item.score() >= 70)
                .sorted(Comparator.comparingInt(LawCandidateSelection.Ranking::score).reversed()).limit(2)
                .map(item -> candidates.stream().filter(c -> c.id().equals(item.candidateId())).findFirst().orElseThrow()).toList();
    }

    public List<LawCandidateSelection.Ranking> rank(String question, QuestionRetrievalPlan plan,
                                                    EvidenceDecision.LawSearch condition, List<Candidate> candidates) {
        if (candidates.isEmpty()) return List.of();
        try {
            var input = mapper.createObjectNode().put("question", question);
            input.set("plan", mapper.valueToTree(plan));
            input.set("condition", mapper.valueToTree(condition));
            input.set("candidates", mapper.valueToTree(candidates));
            String output = client.prompt().user(mapper.writeValueAsString(input)).call().content();
            if (output == null || output.length() > 16384) throw new IllegalArgumentException();
            LawCandidateSelection selection = mapper.readerFor(LawCandidateSelection.class)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .without(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .with(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES).readValue(output);
            Set<String> supplied = candidates.stream().map(Candidate::id).collect(Collectors.toSet());
            if (selection == null || selection.rankings().size() != supplied.size()
                    || !supplied.equals(selection.rankings().stream()
                    .map(LawCandidateSelection.Ranking::candidateId).collect(Collectors.toSet())))
                throw new IllegalArgumentException();
            return selection.rankings();
        } catch (Exception invalid) {
            throw new GptApiException(ErrorCode.GPT_API_FAILURE);
        }
    }
}
