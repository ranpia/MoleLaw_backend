# 상담방 최초 근거와 인용 검증

## 저장과 사용

LegalEvidenceCollector가 충분성 판단에서 선택한 법령 조문을 EvidenceItem으로 전달하고 `LegalAnswerService.prepareInitialAnswer`가 최초 답변을 생성합니다. 각 항목은 서버가 만든 UUID, 종류, 원본 식별자, 제목, 출처 URL, 사용한 본문을 갖습니다. 법령 청크는 전체 법령 원문을 의미하지 않으며 `CASE_METADATA`는 판시사항·판결 이유의 근거가 아닙니다.

모델이 이 ID로 인용한 답변을 생성하면 서버가 검증합니다. `ChatTurnService.saveInitialTurn`은 모델 호출이 끝난 후 방·근거·암호화된 USER/BOT만 한 트랜잭션으로 저장합니다. INFO는 저장하지 않고 조회 시 스냅샷에서 렌더링해 최초 BOT 다음에 삽입합니다. 실패하면 방도 남기지 않습니다. 후속 USER/BOT도 같은 서비스의 별도 공개 트랜잭션에서 함께 저장합니다. 근거가 없으면 최초 답변을 생성하지 않습니다. [저장·암호화·한도와 조회 계약](chat-storage.md)을 따릅니다.

`chat_evidence_snapshot`은 `room_id` 기본 키로 방당 한 행만 보관합니다. 최초 근거 JSON·SHA-256·저장 시각을 고정하고 덮어쓰지 않습니다. 최대 100항목·UTF-8 JSON 2MiB이며 방을 삭제하면 함께 삭제합니다. 원문 저장이나 벡터 색인이 바뀌어도 이 사본은 바뀌지 않습니다. Flyway V2로 테이블을 생성합니다.

## 후속 상담

`ChatEvidenceService.load(userId, roomId)`가 소유권·스냅샷 해시·필드를 확인한 뒤 고정 ID·종류·제목·본문을 답변 Client에 제공합니다. `MessageChatMemoryAdvisor`는 별도로 대화 기록만 복원합니다. 대화 속 이전 인용이나 INFO를 원문 근거로 재해석하지 않습니다. 법령 추가 검색·ETL은 수행하지 않습니다. 사용자 동의 후 판례 본문 보강과 별도 근거 조회는 [후속 상담 계약](follow-up-consultation.md)을 따릅니다.

스냅샷이 없는 기존 방은 실패 처리합니다(`GPT_API_FAILURE`). 이전 대화만으로 근거를 추정해 자동 보충하지 않습니다. 새 상담방에서 다시 시작해야 하며 별도 이관 기능은 제공하지 않습니다.

## 인용 계약

- 모델은 `[[xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx]]` 형식의 소문자 UUID로 인용합니다.
- `CitationValidator`는 최초·후속 답변 모두에서 허용된 ID 소속, 형식, 인용 누락을 검사합니다. 다른 방 ID·새로 만든 ID·잘못된 마커는 거절합니다.
- 모델이 작성한 HTTP(S) URL도 거절합니다. 출처 링크와 ID 안내는 최초 INFO에서 서버가 생성합니다. 반환 JSON 필드는 유지하며 답변 본문에 인용 ID가 포함됩니다.
- 후속 스트리밍의 인용 표기 오류는 같은 확인 근거로 1회 교정하고 재검증합니다. 교정에도 실패하면 저장하지 않고 오류로 종료합니다. 검색은 반복하지 않습니다.

## 실시간 표시와 사용자 삭제

검색·검토 중 확정된 근거는 SSE/NDJSON의 `evidence` 이벤트로 전달한다. `evidence.state=PREVIEW`일 때는 화면에서만 표시하고 DB에는 저장하지 않는다. 답변이 성공하면 `SAVED`의 실제 저장 목록으로 교체한다. 실패하면 미리보기를 폐기하고 기존 목록을 복원한다. [이벤트 구조와 프론트 처리](initial-answer-stream.md)를 따른다.

`DELETE /api/chat-rooms/{roomId}/evidence/{evidenceId}`는 본인 방의 저장된 근거를 제거한다. 인증과 `X-XSRF-TOKEN`이 필요하며 요청 본문 없이 호출하고 성공은 본문 없는 **204**다. 없는 방은 `404 CHATROOM_NOT_FOUND`, 타인 방은 `403 UNAUTHORIZED_CHATROOM_ACCESS`, 없는·이미 삭제한 근거는 `404 NOT_FOUND`, UUID 형식 오류는 `400 INVALID_REQUEST`다.

삭제 후 목록·상세·다음 상담의 근거에서 제외한다. 최초 스냅샷 JSON과 해시는 변경하지 않고 V8 `chat_evidence_removal`에 방별 제외 기록을 남긴다. 추가 판례는 해당 방의 참조도 제거한다. 공유 `legal_source` 원문과 다른 방은 보존한다. 기존 메시지를 다시 쓰지 않으므로 과거 답변의 삭제된 근거 링크는 404일 수 있다. 모든 근거를 삭제할 수 있으며, 이후에는 남은 확인 근거가 없음을 모델에 명시한다. 방 삭제 시 제외 기록도 cascade 삭제한다.

현재 실행 중인 생성 요청은 시작 시의 근거를 사용할 수 있으므로 저장된 근거 삭제는 생성 완료 후 수행한다. `PREVIEW` 카드의 삭제는 로컬 숨김 ID로 기록하고, 성공 후 `SAVED.items`에 그 ID가 있으면 DELETE를 호출한 뒤 GET으로 목록을 재조회한다. 아직 저장되지 않은 PREVIEW ID에 DELETE를 호출하면 404다. PREVIEW 카드를 숨겨도 이미 진행 중인 모델 입력은 바뀌지 않는다.

검증 범위는 **고정 근거의 식별과 인용 계약**입니다. 문장의 법적 결론이 인용 본문에서 도출되는지, 모든 주장에 적절한 인용이 있는지, 최신·시행 법령인지까지 판정하지 않습니다. 충분성 판단·최초 수집 반복은 연결했지만 실제 의미 충실성 평가는 미완료입니다. 전체 근거·대화의 토큰 창 보강은 [P06](../plans/README.md)에서 관리합니다.

## 검증

`.\gradlew.bat test`는 최초 근거 복사·인용 허용/거절·소유권·해시·Advisor 주입과 H2의 실제 저장·삭제·트랜잭션을 검증합니다. `.\gradlew.bat mysqlIntegrationTest`는 격리 MySQL에서 V1~V8와 JPA 매핑·판례 참조 보존·삭제를 검증합니다. 모델 API는 모킹하며 실제 법률 답변 품질 검증은 별도입니다.

AI Search 경로는 LAW_AI_SEARCH 조문 식별자·출처를 검증해 같은 방 스냅샷에 고정할 수 있습니다. 실 API 정상 응답은 미검증이며 판례 자동 본문 수집·채택과는 별개입니다. [AI Search](../law/ai-search-comparison.md)·[사용자 판단 D06](../decisions/README.md)을 참고하세요.
