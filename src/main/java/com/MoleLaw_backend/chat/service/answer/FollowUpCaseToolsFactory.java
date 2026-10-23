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

    public Session create(List<EvidenceItem> roomEvidence) { return create(roomEvidence, false); }
    public Session create(List<EvidenceItem> roomEvidence, boolean allowCaseSearch) {
        return new Session(api, reader, roomEvidence, allowCaseSearch);
    }

    public static final class Session {
        private final LegalApiToolService api;
        private final CaseEvidenceReader reader;
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

        private Session(LegalApiToolService api, CaseEvidenceReader reader, List<EvidenceItem> roomEvidence, boolean allowCaseSearch) {
            this.api = api;
            this.reader = reader;
            this.roomEvidence = List.copyOf(roomEvidence);
            this.allowCaseSearch = allowCaseSearch;
            var clock = Clock.systemUTC();
            context = new LegalToolContext(LegalToolContext.Purpose.FOLLOW_UP_CASE, UUID.randomUUID().toString(),
                    clock.instant().plus(Duration.ofSeconds(90)), 8, clock);
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
            log.info("Follow-up cases executionId={} stage=CONSENT_REQUIRED", context.executionId());
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

        @Tool(name = "searchRoomCases", description = "이 방의 법령 근거 ID를 기준으로 관련 판례 목록을 검색합니다. 목록은 인용 근거가 아닙니다. 본문은 readRoomCase로 읽으세요. hasNext이면 예산 안에서 다음 페이지를 지정할 수 있습니다.")
        public synchronized Map<String, Object> search(
                @ToolParam(description = "현재 방의 법령 근거 UUID") String lawEvidenceId,
                @ToolParam(description = "현재 질문의 쟁점 검색어, 1~200자") String query,
                @ToolParam(description = "검색 페이지, 1~10000") int page) {
            beginCall();
            if (!allowCaseSearch) throw new IllegalStateException("Explicit precedent search consent required");
            var anchor = anchor(lawEvidenceId);
            String lawName = anchor.title().replaceFirst("\\s+제[0-9]+조.*$", "").strip();
            try {
                log.info("Follow-up cases executionId={} stage=CASE_SEARCH page={}", context.executionId(), page);
                var result = api.searchCasesForLaw(context, new LegalToolContracts.SearchRequest(query, 2, null, page, 10), lawName);
                log.info("Follow-up cases executionId={} stage=CASE_CANDIDATES status={} count={} hasNext={}",
                        context.executionId(), result.status(), result.candidates().size(), result.hasNext());
                result.candidates().forEach(candidate -> candidateIds.add(candidate.id()));
                return Map.of("status", result.status().name(), "lawEvidenceId", lawEvidenceId,
                        "referenceLaw", lawName, "candidates", result.candidates(), "page", result.page(),
                        "hasNext", result.hasNext(), "totalCount", result.totalCount());
            } catch (LegalToolException failure) {
                log.warn("Follow-up cases executionId={} stage=CASE_SEARCH reason={}", context.executionId(), failure.getReason());
                return Map.of("status", "UNAVAILABLE", "reason", failure.getReason().name());
            }
        }

        @Tool(name = "readRoomCase", description = "방에 저장된 판례 또는 이번 searchRoomCases에서 발견한 판례 ID를 읽어 본문과 인용 UUID를 반환합니다. 새 판례 상세 조회는 최대 2개이며 전체 본문이 예산을 초과하면 읽지 않습니다.")
        public synchronized Map<String, Object> read(@ToolParam(description = "searchRoomCases가 반환한 숫자 판례 ID") String caseId) {
            beginCall();
            if (read.containsKey(caseId)) return Map.of("status", "READ", "evidence", read.get(caseId));
            var existing = roomEvidence.stream().filter(item -> "CASE_BODY".equals(item.kind())
                    && caseId.equals(item.sourceId())).findFirst();
            if (existing.isPresent()) return accept(existing.get());
            if (!allowCaseSearch) throw new IllegalStateException("Explicit precedent collection consent required");
            if (!candidateIds.contains(caseId)) throw new IllegalArgumentException("Unknown searched precedent");
            if (detailAttempts >= 2 || roomEvidence.stream().filter(item -> "CASE_BODY".equals(item.kind())).count()
                    + read.values().stream().filter(item -> !roomEvidence.contains(item)).count() >= 20)
                return Map.of("status", "LIMIT_REACHED");
            try {
                detailAttempts++;
                log.info("Follow-up cases executionId={} stage=CASE_DETAIL", context.executionId());
                var detail = api.getCaseDetail(context, caseId);
                if (!detail.contentAvailable()) return Map.of("status", "NO_BODY");
                var item = reader.read(detail.referenceId(), UUID.randomUUID().toString());
                if (!caseId.equals(item.sourceId())) throw new IllegalArgumentException("Case source mismatch");
                return accept(item);
            } catch (LegalToolException failure) {
                log.warn("Follow-up cases executionId={} stage=CASE_DETAIL reason={}", context.executionId(), failure.getReason());
                return Map.of("status", "UNAVAILABLE", "reason", failure.getReason().name());
            }
        }

        private Map<String, Object> accept(EvidenceItem item) {
            int cost = tokens.estimate(item.content());
            if (cost > 12000 - bodyTokens) return Map.of("status", "BODY_BUDGET_EXCEEDED");
            bodyTokens += cost;
            read.put(item.sourceId(), item);
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
