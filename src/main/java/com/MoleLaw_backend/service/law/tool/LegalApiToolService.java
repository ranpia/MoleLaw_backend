package com.MoleLaw_backend.service.law.tool;

import com.MoleLaw_backend.domain.entity.LegalSource;
import com.MoleLaw_backend.dto.request.LawSearchFilters;
import com.MoleLaw_backend.service.law.LawSearchService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.MoleLaw_backend.service.law.tool.LegalToolContracts.*;
import static com.MoleLaw_backend.service.law.tool.LegalToolException.Reason.*;

/** Explicit server tools. No automatic tool calling or chat memory side effects. */
@Service
@RequiredArgsConstructor
public class LegalApiToolService {
    private final LawSearchService laws;
    private final OpenLawCaseClient cases;
    private final LegalSourceService sources;
    private final ObjectMapper mapper;

    public SearchResult searchLaws(LegalToolContext context, SearchRequest request) {
        return search(context, request, true);
    }

    public SearchResult searchCases(LegalToolContext context, SearchRequest request) {
        if (request.ministry() != null || !LawSearchFilters.NONE.equals(request.lawFilters())) {
            throw new IllegalArgumentException("Cases do not support law filters");
        }
        return search(context, request, false);
    }

    private SearchResult search(LegalToolContext context, SearchRequest request, boolean law) {
        synchronized (context) {
            context.authorize();
            String sourceUrl = "https://www.law.go.kr/DRF/lawSearch.do?target=" + (law ? "eflaw" : "prec");
            String condition = (law ? "LAW:" : "CASE:") + new SearchRequest(request.query(),
                    request.search(), request.ministry(), 1, request.display(), request.lawFilters());
            String key = condition + ":" + request.page();
            if (context.completed.contains(key)) return result(context, request, Status.DUPLICATE, sourceUrl);
            if (context.exhausted.contains(condition)) return result(context, request, Status.EXHAUSTED, sourceUrl);
            if (request.page() >= context.endPages.getOrDefault(condition, Integer.MAX_VALUE)) {
                return result(context, request, Status.EXHAUSTED, sourceUrl);
            }
            if (!context.hasBudget()) return result(context, request, Status.BUDGET_EXHAUSTED, sourceUrl);
            context.reserve();
            JsonNode root;
            try {
                root = law ? mapper.readTree(searchLawPage(request)).path("LawSearch")
                        : cases.search(request.query(), request.search(), request.page(), request.display());
            } catch (Exception failure) {
                throw apiFailure(failure);
            }
            context.checkDeadline();
            if (root == null || !root.isObject() || !root.has("totalCnt")
                    || !root.path("totalCnt").asText().matches("[0-9]{1,9}")) throw invalid();
            int total = root.path("totalCnt").asInt();
            if (root.has("page") && root.path("page").asInt(-1) != request.page()) throw invalid();
            JsonNode rows = root.path(law ? "law" : "prec");
            List<JsonNode> nodes = rows(rows);
            if ((total == 0 && !nodes.isEmpty()) || nodes.size() > request.display() || (total > 0 && nodes.isEmpty()
                    && (long) (request.page() - 1) * request.display() < total)) throw invalid();
            List<Candidate> parsed = new ArrayList<>();
            for (JsonNode node : nodes) {
                String id = node.path(law ? "법령일련번호" : "판례일련번호").asText();
                if (!id.matches("[0-9]{1,20}")) throw invalid();
                String title = node.path(law ? "법령명한글" : "사건명").asText();
                if (title.isBlank() || title.length() > 1000) throw invalid();
                if (law && request.lawFilters().lawId() != null && !sameLawId(
                        request.lawFilters().lawId(), node.path("법령ID").asText())) throw invalid();
                parsed.add(new Candidate(id, law ? shortText(node, "법령ID") : null, title,
                        law ? shortText(node, "시행일자") : null,
                        law ? null : shortText(node, "사건번호"),
                        law ? null : shortText(node, "선고일자"),
                        law ? null : shortText(node, "법원명")));
            }
            Set<String> seen = context.seen.computeIfAbsent(condition, ignored -> new HashSet<>());
            Map<String, Candidate> allowed = law ? context.laws : context.cases;
            List<Candidate> fresh = new ArrayList<>();
            int newCandidates = 0;
            for (Candidate candidate : parsed) {
                boolean isNew = seen.add(candidate.id() + ":" + candidate.lawId());
                if (isNew) newCandidates++;
                if (law && request.lawFilters().exactName() && !request.query().equals(candidate.title())) continue;
                if (isNew) fresh.add(candidate);
                allowed.putIfAbsent(candidate.id(), candidate);
            }
            context.completed.add(key);
            boolean hasNext = newCandidates > 0 && (long) request.page() * request.display() < total;
            // An out-of-range page does not exhaust unvisited earlier pages.
            if (newCandidates == 0 && !parsed.isEmpty()) context.exhausted.add(condition);
            if (parsed.isEmpty()) context.endPages.merge(condition, request.page(), Math::min);
            else if (!hasNext) context.endPages.merge(condition, request.page() + 1, Math::min);
            Status status = parsed.isEmpty() || (fresh.isEmpty() && newCandidates > 0)
                    ? Status.EMPTY : fresh.isEmpty() ? Status.EXHAUSTED : Status.AVAILABLE;
            return new SearchResult(status, fresh, request.page(), request.display(), total,
                    hasNext, context.now(), sourceUrl);
        }
    }

    public DetailResult getLawDetail(LegalToolContext context, String mst) {
        return detail(context, mst, true);
    }

    private String searchLawPage(SearchRequest request) {
        if (LawSearchFilters.NONE.equals(request.lawFilters())) {
            return laws.searchPage(request.query(), request.search(), request.ministry(), request.page(), request.display());
        }
        return laws.searchPage(request.query(), request.search(), request.ministry(), request.page(),
                request.display(), request.lawFilters());
    }

    private static boolean sameLawId(String requested, String actual) {
        return actual != null && actual.matches("[0-9]{1,20}")
                && new java.math.BigInteger(requested).equals(new java.math.BigInteger(actual));
    }

    public DetailResult getCaseDetail(LegalToolContext context, String id) {
        return detail(context, id, false);
    }

    private DetailResult detail(LegalToolContext context, String id, boolean law) {
        synchronized (context) {
            context.authorize();
            if (id == null || !id.matches("[0-9]{1,20}")) throw new IllegalArgumentException("Invalid detail ID");
            Candidate candidate = (law ? context.laws : context.cases).get(id);
            if (candidate == null) throw new LegalToolException(UNKNOWN_ID, false);
            String key = (law ? "LAW:" : "CASE:") + id;
            if (context.details.containsKey(key)) return context.details.get(key);
            context.reserve();
            JsonNode content;
            try {
                content = law ? laws.getLawDetailByMst(id) : cases.detail(id);
            } catch (Exception failure) {
                throw apiFailure(failure);
            }
            context.checkDeadline();
            if (content == null || !content.isObject() || content.isEmpty()) throw invalid();
            JsonNode info = law ? content.path("기본정보") : content;
            String title = info.path(law ? "법령명_한글" : "사건명").asText();
            String lawId = law ? info.path("법령ID").asText() : null;
            String effectiveDate = law ? info.path("시행일자").asText() : null;
            if (title.isBlank()) throw invalid();
            if (title.length() > 1000 || (law && (!lawId.matches("[0-9]{1,20}")
                    || (!effectiveDate.isBlank() && !effectiveDate.matches("[0-9]{8}"))))) throw invalid();
            if (law && candidate.lawId() != null && !candidate.lawId().isBlank()
                    && !candidate.lawId().equals(lawId)) throw invalid();
            if (law && candidate.effectiveDate() != null && !candidate.effectiveDate().isBlank()
                    && !candidate.effectiveDate().equals(effectiveDate)) throw invalid();
            if (!law && !id.equals(content.path("판례정보일련번호").asText(
                    content.path("판례일련번호").asText()))) throw invalid();
            int articleCount = law ? rows(content.path("조문").path("조문단위")).size() : 0;
            List<String> articleNumbers = law ? rows(content.path("조문").path("조문단위")).stream()
                    .limit(100).map(article -> shortText(article, "조문번호")).toList() : List.of();
            boolean available = law ? articleCount > 0 : !content.path("판례내용").asText().isBlank();
            String url = law ? "https://www.law.go.kr/법령?lsId=" + lawId + "&lsiSeq=" + id
                    : "https://www.law.go.kr/precInfoP.do?precSeq=" + id;
            LegalSource source = sources.save(law ? "LAW" : "CASE", id, lawId, effectiveDate, url, content);
            DetailResult result = new DetailResult(source.getId(), id, lawId, effectiveDate, title,
                    articleCount, available, source.getCollectedAt(), url, articleNumbers);
            context.details.put(key, result);
            return result;
        }
    }

    private static List<JsonNode> rows(JsonNode rows) {
        if (rows.isMissingNode() || rows.isNull()) return List.of();
        if (rows.isObject()) return List.of(rows);
        if (!rows.isArray()) throw invalid();
        List<JsonNode> result = new ArrayList<>();
        rows.forEach(result::add);
        return result;
    }

    private static String shortText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.length() > 1000) throw invalid();
        return value;
    }

    private SearchResult result(LegalToolContext context, SearchRequest request, Status status, String url) {
        return new SearchResult(status, List.of(), request.page(), request.display(), 0, false, context.now(), url);
    }

    private static LegalToolException invalid() { return new LegalToolException(INVALID_RESPONSE, false); }

    private static LegalToolException apiFailure(Exception failure) {
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException
                || failure instanceof NullPointerException) return invalid();
        if (failure instanceof OpenLawRequestException request) {
            return new LegalToolException(API_FAILURE, request.isRetryable());
        }
        if (failure instanceof com.MoleLaw_backend.exception.OpenLawApiException api
                && api.getErrorCode() == com.MoleLaw_backend.exception.ErrorCode.OPENLAW_INVALID_RESPONSE) {
            return invalid();
        }
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof WebClientResponseException response) {
                int status = response.getStatusCode().value();
                return new LegalToolException(API_FAILURE, status == 429 || status >= 500);
            }
            cause = cause.getCause();
        }
        return new LegalToolException(API_FAILURE,
                failure instanceof com.MoleLaw_backend.exception.OpenLawApiException
                || failure instanceof org.springframework.web.reactive.function.client.WebClientRequestException
                || failure instanceof IllegalStateException);
    }
}
