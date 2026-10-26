# 후속 상담과 판례 보강

기준일: 2026-10-23, dev 작업 트리. 최초 법령 수집·ETL은 유지하고 후속 상담에서만 동의 기반 판례 도구를 제공한다.

## 대화와 동의

`POST /api/chat-rooms/{roomId}/messages`는 소유권 검사 후 Advisor로 처음 질문·답변과 최근 대화를 복원한다. 이전 답변은 맥락이며 원문 근거가 아니다. 사용자의 새 진술을 확인된 사실로 임의 확정하지 않는다.

기본 요청은 다음과 같다.

```json
{"content":"이 경우 판례도 필요한가요?","allowCaseSearch":false}
```

판례가 필요하면 모델이 `requestCaseSearchConsent`에 방 법령 근거 ID와 이유를 전달한다. 이 도구는 외부 API를 호출하지 않는다. 봇은 동의를 묻고 응답의 `caseSearchRequested=true`를 반환한다. 이 표시와 동의 질문은 메시지와 함께 저장되어 기록 조회에서도 유지된다.

사용자가 승인한 경우에만 다음 요청을 보낸다.

```json
{"content":"네, 관련 판례를 찾아주세요.","allowCaseSearch":true}
```

동의는 **해당 요청에만** 적용한다. 모델이 대화의 “네”나 이전 동의를 권한으로 해석할 수 없다. UI는 승인 동작에서만 true를 전송하고 거절·일반 질문은 false 또는 생략한다. 별도의 승인 토큰이나 버튼 UI는 백엔드에서 생성하지 않는다.

## 판례 도구

- 동의 전에는 외부 검색 도구 자체를 모델에 노출하지 않는다. 직접 호출 경로도 서버가 차단한다.
- `searchRoomCases(lawEvidenceId, query, page)`는 현재 방의 법령 ID만 받는다. 서버가 조문 제목에서 법령명을 구해 공식 API의 참조법령 `JO` 필터로 지정한다. 목록은 본문 근거가 아니다.
- `readRoomCase(caseId)`는 이번 검색에서 확인한 판례나 이미 방에 저장된 판례만 읽는다. 임의 판례 ID·원문 UUID·URL은 허용하지 않는다. 저장된 판례는 동의 없이도 외부 호출 없이 복원한다.
- 본문과 원문 SHA-256을 검증하고 전체 판례내용·판시사항·참조조문 등을 읽는다. 모델이 관련성을 검토한 후 서버가 만든 근거 UUID로 인용한다.
- 한 요청의 도구 실행은 최대 8회·90초, 새 상세 조회는 2개, 판례 본문 누적 추정량은 12,000토큰이다. 잘라낸 판결문을 전체 본문으로 제공하지 않는다. 예산 초과·빈 검색·API 장애는 구분한다. 이 값은 초기 구현 한도이며 실제 LLM usage 비용 정산과 다르다.

법령 재수집·임베딩 도구는 후속 상담에서 사용할 수 없다. 기존 판례는 목록만 전달하고 필요할 때 다시 읽으므로, 방에 판례가 쌓여도 모든 본문을 매번 프롬프트에 넣지 않는다.

## 저장과 인용

최초 `chat_evidence_snapshot`은 덮어쓰지 않는다. 새로 읽고 실제 답변에서 인용한 판례만 `chat_case_evidence`에 불변 원문 UUID와 근거 UUID로 추가한다. 방·판례일련번호의 유일성을 검사하고 최대 20건을 보관한다. 기존 판례의 근거 ID는 후속 요청에서도 유지한다.

답변은 이번에 제공한 법령 본문·읽은 판례 ID로만 인용할 수 있다. 목록 ID·미등록 ID·다른 방 ID·모델 URL은 거절한다. 성공한 질문·답변·판례 참조는 한 트랜잭션으로 저장한다. 실패하면 추가 참조와 메시지를 남기지 않는다. 앞서 확보한 공식 원문은 재사용·감사를 위해 남을 수 있다. 방 삭제 시 판례 참조는 cascade 삭제하고 원문은 보존한다.

Flyway **V5**가 `chat_case_evidence`, 메시지의 동의 요청·승인 표시를 추가한다. 기존 V1~V4 SQL과 사용자 데이터를 재작성하지 않는다.

## 근거 조회 API

| API | 반환 |
| --- | --- |
| `GET /api/chat-rooms/{roomId}/evidence` | `id`, `kind`, `sourceId`, `title`, `sourceUrl`, `lawId`, `effectiveDate` 목록. 본문 제외 |
| `GET /api/chat-rooms/{roomId}/evidence/{evidenceId}` | `evidence` 메타데이터, `content` 본문, 판례의 `caseInfo`(사건번호·법원·선고일·판시사항·요지·참조조문·참조판례) |

인증과 방 소유권을 검사한다. GET은 본문·CSRF 헤더가 필요하지 않다. 방이 없거나 소속 근거가 없으면 404, 타인 방은 403이다. 읽기 API는 검색·LLM을 실행하지 않는다. 근거 제목·상세 본문·판례 메타데이터는 표시용 일반 텍스트로 반환한다. HTML 태그를 제거하고 엔티티를 해제하며 문단·줄바꿈을 보존한다. 저장 원문·해시·인용 ID와 LLM 입력은 바꾸지 않는다. 일반·스트리밍 INFO에도 같은 변환을 적용하며 판례는 JSON 대신 요지 발췌를 표시한다. 기존 메시지 조회의 가상 INFO는 호환을 위해 유지한다. 프론트는 반환값을 텍스트로 표시한다.

## 검증과 남은 범위

`test`는 Advisor·권한·인용·동의 차단을 검증한다. Postman 대역으로 공식 판례 HTTP 계약과 성공·장애를 검증하려면 다음을 실행한다.

```powershell
postman simulation run postman/simulations/follow-up-cases.sim.yaml --port 14039 --no-history --output ndjson
$env:CASE_SIMULATION_URL='http://127.0.0.1:14039'
.\gradlew.bat followUpConsultationSimulationTest
.\gradlew.bat mysqlIntegrationTest
```

모델 응답은 fixture이며 실제 법률 품질을 입증하지 않는다. 판례 관련성·사실 정정·장문 대화 토큰 창·동시 후속 요청 직렬화·실제 usage 정산은 별도 평가·보완 대상이다. 사용자 UI의 승인 버튼 연결도 필요하다.
