package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.law.dto.evidence.EvidenceItem;
import com.MoleLaw_backend.law.dto.tool.LegalToolContracts;
import com.MoleLaw_backend.law.exception.LegalToolException;
import com.MoleLaw_backend.law.service.tool.CaseEvidenceReader;
import com.MoleLaw_backend.law.service.tool.LegalApiToolService;
import com.MoleLaw_backend.law.service.tool.LegalToolContext;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tokenizer.JTokkitTokenCountEstimator;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creates isolated, server-bound tools for one authorized room turn. */
@Service
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class FollowUpCaseToolsFactory {
    private final LegalApiToolService api;
    private final CaseEvidenceReader reader;
    private final com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline pipeline;

    public Session create(List<EvidenceItem> roomEvidence) { return create(roomEvidence, false); }
    public Session create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch) {
        return create(roomEvidence, allowCaseSearch, UUID.randomUUID().toString(), stage -> {});
    }
    public Session create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, java.util.function.Consumer<String> progress) {
        return create(roomEvidence, allowCaseSearch, traceId, null, progress);
    }
    public Session create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, Long roomId, java.util.function.Consumer<String> progress) {
        return new Session(api, reader, pipeline, roomEvidence, allowCaseSearch, traceId, roomId, progress);
    }

    public static final class Session {
        private final LegalApiToolService api;
        private final CaseEvidenceReader reader;
        private final com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline pipeline;
        private final com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline.State searchState;
        private final Long roomId;
        private final Map<String, EvidenceItem> prefetched = new LinkedHashMap<>();
        private final java.util.function.Consumer<String> progress;
        private final List<EvidenceItem> roomEvidence;
        private final Map<String, EvidenceItem> read = new LinkedHashMap<>();
        private final java.util.Set<String> candidateIds = new java.util.HashSet<>();
        private final LegalToolContext context;
        private final JTokkitTokenCountEstimator tokens = new JTokkitTokenCountEstimator(EncodingType.CL100K_BASE);
        private int toolCalls;
        private int bodyTokens;
        private int detailAttempts;
        private final boolean allowCaseSearch;
        private boolean consentRequested;
        private String consentAnchorId;
        private final List<String> searchReports = new ArrayList<>();
        public String searchSummary() { return String.join("\n", searchReports); }

        private Session(LegalApiToolService api, CaseEvidenceReader reader, com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline pipeline,
                List<EvidenceItem> roomEvidence, boolean allowCaseSearch, String traceId, Long roomId, java.util.function.Consumer<String> progress) {
            this.api = api;
            this.reader = reader;
            this.pipeline = pipeline;
            this.progress = progress;
            this.roomId = roomId;
            this.searchState = new com.MoleLaw_backend.law.service.retrieval.CaseSearchPipeline.State(roomId);
            this.roomEvidence = List.copyOf(roomEvidence);
            this.allowCaseSearch = allowCaseSearch;
            var clock = Clock.systemUTC();
            context = new LegalToolContext(LegalToolContext.Purpose.FOLLOW_UP_CASE, traceId,
                    clock.instant().plus(Duration.ofSeconds(90)), Math.max(8, pipeline.maxApiCalls()), clock);
        }

        @Tool(name = "requestCaseSearchConsent", description = "새 판례의 외부 검색은 사용자 동의가 필요합니다. 방의 법령 근거 ID와 검색할 이유를 지정하고 최종 답변에서 동의를 물으세요. 외부 API는 호출하지 않습니다.")
        public synchronized Map<String, Object> requestConsent(
                @ToolParam(description = "현재 방의 법령 근거 UUID") String lawEvidenceId,
                @ToolParam(description = "판례 검색으로 보강할 쟁점과 이유, 1~300자") String reason) {
            beginCall();
            anchor(lawEvidenceId);
            if (reason == null || reason.isBlank() || reason.length() > 300) throw new IllegalArgumentException("Invalid consent reason");
            if (allowCaseSearch) return Map.of("status", "ALREADY_GRANTED");
            consentRequested = true;
            consentAnchorId = lawEvidenceId;
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=동의요청", context.executionId(), roomId);
            return Map.of("status", "CONSENT_REQUIRED", "lawEvidenceId", lawEvidenceId, "reason", reason);
        }

        private EvidenceItem anchor(String lawEvidenceId) {
            return roomEvidence.stream().filter(item -> item.id().equals(lawEvidenceId)
                    && item.kind().startsWith("LAW_")).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown room law evidence"));
        }

        public boolean consentRequested() { return consentRequested; }
        public boolean consentGranted() { return allowCaseSearch; }

        public String consentMessage() {
            var law = anchor(consentAnchorId);
            return "현재 상담방의 법령 근거 [[" + law.id() + "]]와 관련된 판례를 검색해 근거를 보강할까요? "
                    + "동의하신 경우에만 새 판례를 조회하겠습니다.";
        }

        private void beginCall() {
            if (++toolCalls > 8) throw new IllegalStateException("Follow-up case tool call limit exceeded");
            context.checkDeadline();
        }

        public synchronized Map<String, Object> search(
                String lawEvidenceId, String query, int page) {
            return searchSelectedScope(lawEvidenceId, query, page, null);
        }

        @Tool(name = "searchRoomCases", description = "판례 검색 범위는 scope로 선택합니다. 기본 BODY는 본문, TITLE은 사건명, LAW_REFERENCE는 방 법령의 참조법령 필터를 적용한 본문 검색입니다. 범위 선택은 LLM이 판단하며 매번 모두 실행하지 않습니다. 쟁점을 법률용어로 변환하고 선택 범위의 전체 페이지를 예산 안에서 수집한 뒤 본문 발췌를 리랭크합니다. searchComplete=false는 목록 미완료이며 판례 부재가 아닙니다. 채택 후보는 readRoomCase로 읽어야 인용 가능합니다.")
        public synchronized Map<String, Object> searchSelectedScope(
                @ToolParam(description = "현재 방의 법령 근거 UUID") String lawEvidenceId,
                @ToolParam(description = "대화에서 파악한 법적 쟁점, 1~200자") String query,
                @ToolParam(description = "서버가 전체 페이지를 순회하므로 1을 지정") int page,
                @ToolParam(description = "선택 검색 범위. 생략하면 BODY", required = false) com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope scope) {
            beginCall();
            if (!allowCaseSearch) throw new IllegalStateException("Explicit precedent search consent required");
            var anchor = anchor(lawEvidenceId);
            String lawName = anchor.title().replaceFirst("\\s+제[0-9]+조.*$", "").strip();
            searchState.scope(scope);
            try {
                var result = pipeline.search(context, anchor, query, page, searchState, progress);
                if (result == null) return Map.of("status", "UNAVAILABLE", "reason", "PIPELINE_UNAVAILABLE");
                String scopeName = scope == com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.TITLE ? "사건명" : scope == com.MoleLaw_backend.law.dto.retrieval.CaseSearchScope.LAW_REFERENCE ? "참조법령 조건의 본문" : "본문";
                searchReports.add("선택한 검색 범위: " + scopeName + ", 수집 후보 " + result.uniqueCount()
                        + "건, 본문 관련성 검토 " + result.rankings().size() + "건, 읽기 후보 " + result.candidates().size()
                        + "건. " + com.MoleLaw_backend.chat.util.UserFacingAnswerText.normalize(result.status())
                        + (result.searchComplete() ? ". 선택한 검색 범위의 목록 수집 완료" : ". 예산 제한으로 전체 목록 수집을 완료하지 못함")
                        + ". 검색 조건과 예산 안에서 확인한 결과이며 판례 자체의 부재를 뜻하지 않습니다.");
                result.candidates().forEach(candidate -> candidateIds.add(candidate.id()));
                prefetched.putAll(result.bodies());
                var response = new LinkedHashMap<String, Object>(Map.of("status", result.status(), "lawEvidenceId", lawEvidenceId,
                        "referenceLaw", lawName, "candidates", result.candidates(), "page", 1,
                        "hasNext", result.hasNext(), "rankings", result.rankings().stream()
                                .filter(rank -> result.bodies().containsKey(rank.candidateId())).toList(),
                        "routeCounts", Map.of("law", result.lawCount(), "facts", result.factCount(), "unique", result.uniqueCount(), "bodyReviewed", result.rankings().size()),
                        "unavailableRoutes", Map.of("law", result.lawUnavailable(), "facts", result.factUnavailable()),
                        "queryRevisionRequired", java.util.Set.of("EMPTY", "NO_RELEVANT_CASES", "NO_READABLE_BODY", "DUPLICATE").contains(result.status())));
                response.put("scope", scope == null ? "BODY" : scope.name());
                response.put("searchComplete", result.searchComplete());
                return response;
            } catch (LegalToolException failure) {
                log.warn("판례처리 traceId={} 유형=후속 방={} 단계=검색실패 사유={}", context.executionId(), roomId, failure.getReason());
                searchReports.add("판례 검색·검토를 실행 한도 또는 의존 서비스 문제로 완료하지 못했습니다. 판례 부재를 뜻하지 않습니다.");
                return Map.of("status", "UNAVAILABLE", "reason", failure.getReason().name(), "searchComplete", false);
            }
        }

        @Tool(name = "readRoomCase", description = "방에 저장된 판례 또는 이번 searchRoomCases에서 본문 리랭크로 채택한 판례 ID를 읽어 본문과 인용 UUID를 반환합니다. 이번 답변에 추가할 새 판례 근거는 최대 2개이며 전체 본문이 예산을 초과하면 읽지 않습니다.")
        public synchronized Map<String, Object> read(@ToolParam(description = "searchRoomCases가 반환한 숫자 판례 ID") String caseId) {
            beginCall();
            if (read.containsKey(caseId)) return Map.of("status", "READ", "evidence", read.get(caseId));
            var existing = roomEvidence.stream().filter(item -> "CASE_BODY".equals(item.kind())
                    && caseId.equals(item.sourceId())).findFirst();
            if (existing.isPresent()) return accept(existing.get());
            if (!allowCaseSearch) throw new IllegalStateException("Explicit precedent collection consent required");
            if (!candidateIds.contains(caseId)) throw new IllegalArgumentException("Unknown searched precedent");
            if (read.values().stream().filter(item -> !roomEvidence.contains(item)).count() >= 2)
                return Map.of("status", "LIMIT_REACHED");
            if (prefetched.containsKey(caseId)) return accept(prefetched.get(caseId));
            if (detailAttempts >= 2 || roomEvidence.stream().filter(item -> "CASE_BODY".equals(item.kind())).count()
                    + read.values().stream().filter(item -> !roomEvidence.contains(item)).count() >= 20)
                return Map.of("status", "LIMIT_REACHED");
            try {
                detailAttempts++;
                log.info("판례처리 traceId={} 유형=후속 방={} 단계=본문조회", context.executionId(), roomId);
                var detail = api.getCaseDetail(context, caseId);
                if (!detail.contentAvailable()) return Map.of("status", "NO_BODY");
                var item = reader.read(detail.referenceId(), UUID.randomUUID().toString());
                if (!caseId.equals(item.sourceId())) throw new IllegalArgumentException("Case source mismatch");
                return accept(item);
            } catch (LegalToolException failure) {
                log.warn("판례처리 traceId={} 유형=후속 방={} 단계=본문실패 사유={}", context.executionId(), roomId, failure.getReason());
                return Map.of("status", "UNAVAILABLE", "reason", failure.getReason().name());
            }
        }

        private Map<String, Object> accept(EvidenceItem item) {
            int cost = tokens.estimate(item.content());
            if (cost > 12000 - bodyTokens) {
                log.info("판례처리 traceId={} 유형=후속 방={} 단계=본문제외 판례={} 사유=본문예산초과", context.executionId(), roomId, item.sourceId());
                return Map.of("status", "BODY_BUDGET_EXCEEDED");
            }
            bodyTokens += cost;
            read.put(item.sourceId(), item);
            progress.accept("CASE_BODY_READY");
            log.info("판례처리 traceId={} 유형=후속 방={} 단계=본문채택 판례={} 본문토큰={} 채택누계={}", context.executionId(), roomId, item.sourceId(), cost, read.size());
            return Map.of("status", "READ", "evidence", item);
        }

        public List<EvidenceItem> evidence() {
            var result = new ArrayList<>(roomEvidence.stream().filter(item -> !"CASE_BODY".equals(item.kind())).toList());
            result.addAll(read.values());
            return List.copyOf(result);
        }

        public List<EvidenceItem> citedAdditions(String answer) {
            return read.values().stream().filter(item -> !roomEvidence.contains(item)
                    && answer.contains("[[" + item.id() + "]]")).toList();
        }
    }
}
