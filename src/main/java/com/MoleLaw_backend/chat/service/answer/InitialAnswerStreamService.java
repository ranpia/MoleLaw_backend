package com.MoleLaw_backend.chat.service.answer;

import com.MoleLaw_backend.chat.dto.response.InitialAnswerStreamResponse;
import com.MoleLaw_backend.chat.service.ChatService;
import com.MoleLaw_backend.common.exception.ErrorCode;
import com.MoleLaw_backend.common.exception.MolelawException;
import com.MoleLaw_backend.user.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;

@Service
@Slf4j
public class InitialAnswerStreamService {
    private static final Map<String, String> STAGES = Map.ofEntries(
            Map.entry("QUESTION_PLANNING", "질문의 쟁점을 분석하고 있습니다."),
            Map.entry("INITIAL_SEARCH", "관련 조문을 검색하고 있습니다."),
            Map.entry("DECIDING", "조문 본문을 읽고 근거가 충분한지 검토하고 있습니다."),
            Map.entry("SEARCH_AGAIN", "검색 쟁점과 조건을 조정하고 있습니다."),
            Map.entry("LAW_SEARCH", "추가로 필요한 법령을 찾고 있습니다."),
            Map.entry("CANDIDATE_REVIEW", "수집할 법령의 관련성을 검토하고 있습니다."),
            Map.entry("LAW_DETAIL", "법령 원문을 수집하고 있습니다."),
            Map.entry("LAW_INDEX", "새 조문을 검색할 수 있도록 준비하고 있습니다."),
            Map.entry("LAW_INDEX_REUSE", "저장된 법령의 검색 색인을 확인하고 있습니다."),
            Map.entry("SEARCH_AFTER_INDEX", "확보한 법령에서 조문을 다시 검색하고 있습니다."),
            Map.entry("ANSWER_GENERATION", "확인한 근거를 바탕으로 답변을 작성하고 있습니다."),
            Map.entry("ANSWER_VALIDATION", "답변의 인용과 근거를 확인하고 있습니다."),
            Map.entry("CONVERSATION_LOADING", "이전 대화와 상담방 근거를 확인하고 있습니다."),
            Map.entry("FOLLOW_UP_PREPARATION", "질문에 필요한 근거와 판례를 검토하고 있습니다."),
            Map.entry("FOLLOW_UP_ROUTING", "새 판례 검색이 필요한 요청인지 확인하고 있습니다."),
            Map.entry("CASE_QUERY_PLANNING", "상담 쟁점을 판례 검색용 법률용어로 바꾸고 있습니다."),
            Map.entry("CASE_CANDIDATE_SELECTION", "찾으시는 사건의 본문을 확인할 후보를 고르고 있습니다."),
            Map.entry("CASE_BODY_READY", "확인한 판례를 근거 목록에 추가하고 있습니다."),
            Map.entry("CASE_SEARCH_LAW", "관련 법령을 참조한 판례를 검색하고 있습니다."),
            Map.entry("CASE_SEARCH_TITLE", "사건명을 기준으로 판례 전체 목록을 검색하고 있습니다."),
            Map.entry("CASE_SEARCH_FACT", "사건의 쟁점을 기준으로 판례를 검색하고 있습니다."),
            Map.entry("CASE_BODY_REVIEW", "판례 후보의 실제 본문을 확보하고 있습니다."),
            Map.entry("CASE_RERANK", "판례 본문과 질문의 관련성을 비교하고 있습니다."),
            Map.entry("SAVING", "검증된 답변과 근거를 저장하고 있습니다."));
    private final LegalAnswerService answers;
    private final ChatService chats;
    private final Semaphore slots;
    private final Duration timeout;

    public InitialAnswerStreamService(LegalAnswerService answers, ChatService chats,
            @Value("${app.chat-stream.max-concurrent:4}") int maxConcurrent,
            @Value("${app.chat-stream.timeout:20m}") Duration timeout) {
        if (maxConcurrent < 1 || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid stream limits");
        this.answers = answers;
        this.chats = chats;
        this.slots = new Semaphore(maxConcurrent);
        this.timeout = timeout;
    }

    public Flux<ServerSentEvent<InitialAnswerStreamResponse>> stream(String question, User owner) {
        return stream(question, owner, "SSE");
    }

    public Flux<ServerSentEvent<InitialAnswerStreamResponse>> stream(String question, User owner, String format) {
        return stream(question, owner, null, false, format);
    }

    public Flux<InitialAnswerStreamResponse> followUp(String question, User owner, Long roomId, boolean allowCaseSearch) {
        return stream(question, owner, roomId, allowCaseSearch, "NDJSON").map(ServerSentEvent::data);
    }

    private Flux<ServerSentEvent<InitialAnswerStreamResponse>> stream(String question, User owner, Long roomId,
            boolean allowCaseSearch, String format) {
        return Flux.defer(() -> {
            String streamId = java.util.UUID.randomUUID().toString();
            long started = System.nanoTime();
            var deltaCount = new java.util.concurrent.atomic.AtomicInteger();
            String turnType = roomId != null ? "후속" : "최초";
            var loggedRoom = new java.util.concurrent.atomic.AtomicReference<String>(roomId == null ? owner == null ? "무저장" : "신규" : roomId.toString());
            log.info("상담 traceId={} 유형={} 방={} 형식={} 상태=시작", streamId, turnType, loggedRoom.get(), format);
            if (!slots.tryAcquire()) {
                log.warn("상담 traceId={} 유형={} 방={} 상태=실행거절 오류=RETRIEVAL_BUSY", streamId, turnType, loggedRoom.get());
                return Flux.just(event(error(ErrorCode.RETRIEVAL_BUSY)));
            }
            var work = Flux.defer(() -> roomId == null ? answers.streamInitialAnswer(question)
                    : Flux.concat(Flux.just(com.MoleLaw_backend.chat.dto.answer.InitialAnswerUpdate.progress("CONVERSATION_LOADING")),
                            answers.streamFollowUp(owner.getId(), roomId, question, allowCaseSearch)))
                    .publishOn(Schedulers.boundedElastic(), 1)
                    .concatMap(update -> {
                        if (update.followUp() != null) {
                            return Flux.concat(Flux.just(progress("SAVING")), reactor.core.publisher.Mono.fromCallable(() -> {
                                var message = chats.saveStreamedFollowUp(owner, roomId, question, update.followUp());
                                return new InitialAnswerStreamResponse("completed", null, null, null, null,
                                        update.followUp().response(), null, null, message);
                            }));
                        }
                        if (update.prepared() != null) {
                            var saved = owner == null ? reactor.core.publisher.Mono.<com.MoleLaw_backend.chat.dto.response.ChatRoomCreationResponse>empty()
                                    : reactor.core.publisher.Mono.fromCallable(() -> chats.saveStreamedInitialAnswer(owner, question, update.prepared()));
                            var completion = saved.map(room -> new InitialAnswerStreamResponse("completed", null, null, null, null,
                                    update.prepared().response(), room, null))
                                    .defaultIfEmpty(new InitialAnswerStreamResponse("completed", null, null, null, null,
                                            update.prepared().response(), null, null));
                            return owner == null ? completion.flux() : Flux.concat(Flux.just(progress("SAVING")), completion);
                        }
                        return Flux.just(switch (update.type()) {
                            case "progress" -> progress(update.text());
                            case "info" -> new InitialAnswerStreamResponse("info", null, null, update.text(), null, null, null, null);
                            case "answer_delta" -> new InitialAnswerStreamResponse("answer_delta", null, null, null, update.text(), null, null, null);
                            default -> throw new IllegalStateException("Unexpected stream state");
                        });
                    }, 1).timeout(timeout).onErrorResume(failure -> Flux.just(error(
                            failure instanceof MolelawException legal ? legal.getErrorCode() : ErrorCode.GPT_API_FAILURE)));
            var heartbeat = Flux.interval(Duration.ofSeconds(10)).map(tick ->
                    new InitialAnswerStreamResponse("heartbeat", null, null, null, null, null, null, null));
            return Flux.merge(work, heartbeat).takeUntil(data -> "completed".equals(data.type()) || "error".equals(data.type()))
                    .doOnNext(data -> {
                        if ("answer_delta".equals(data.type())) deltaCount.incrementAndGet();
                        else if (!"heartbeat".equals(data.type())) {
                            if (data.room() != null) loggedRoom.set(data.room().getId().toString());
                            log.info("상담 traceId={} 유형={} 방={} 이벤트={} 단계={} 안내={} 오류={}",
                                    streamId, turnType, loggedRoom.get(), data.type(), data.stage(),
                                    "progress".equals(data.type()) ? data.message() : "-", data.code());
                        }
                    }).map(InitialAnswerStreamService::event).doFinally(signal -> {
                        slots.release();
                        log.info("상담 traceId={} 유형={} 방={} 상태=종료 사유={} 처리시간Ms={} 답변조각={}",
                                streamId, turnType, loggedRoom.get(), signal, (System.nanoTime() - started) / 1_000_000, deltaCount.get());
                    }).contextWrite(context -> context.put("consultationTraceId", streamId));
        });
    }

    private static InitialAnswerStreamResponse progress(String stage) {
        return new InitialAnswerStreamResponse("progress", stage, STAGES.getOrDefault(stage, "근거를 확인하고 있습니다."),
                null, null, null, null, null);
    }

    private static InitialAnswerStreamResponse error(ErrorCode code) {
        return new InitialAnswerStreamResponse("error", null, code.getMessage(), null, null, null, null, code.name());
    }

    private static ServerSentEvent<InitialAnswerStreamResponse> event(InitialAnswerStreamResponse data) {
        return ServerSentEvent.builder(data).event(data.type()).build();
    }
}
