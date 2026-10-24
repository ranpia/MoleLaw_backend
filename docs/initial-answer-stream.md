# 상담 SSE·NDJSON

최초 질문 처리 중 실제 작업 단계와 채택된 근거를 먼저 보여 주고, 이어서 답변을 모델 스트림으로 받는다. 질문을 다시 보내거나 후속 상담을 자동으로 한 번 더 실행하지 않는다. 기존 JSON API는 유지한다.

| 경로 | 요청 JSON | 완료 결과 |
| --- | --- | --- |
| `POST /api/chat-rooms/stream` | `{"content":"질문"}` | `completed.response`, `completed.room`(저장된 방과 메시지) |
| `POST /api/legal-answers/stream` | `{"query":"질문"}` | `completed.response`(무저장) |
| `POST /api/chat-rooms/ndjson` | `{"content":"질문"}` | SSE와 같은 이벤트·저장 결과를 JSON 한 줄씩 전달 |
| `POST /api/legal-answers/ndjson` | `{"query":"질문"}` | 같은 이벤트·무저장 완료 결과를 JSON 한 줄씩 전달 |
| `POST /api/chat-rooms/{roomId}/messages/ndjson` | `{"content":"후속 질문","allowCaseSearch":false}` | `completed.response`, `completed.messageResult`(저장한 BOT 메시지·판례 동의 요청 여부) |

`/stream`은 `Accept: text/event-stream`, `/ndjson`은 `Accept: application/x-ndjson`으로 호출한다. NDJSON 응답에는 `event:`/`data:` 접두사나 JSON 배열 없이 독립 JSON 객체 한 개마다 개행이 붙는다. 내부 줄바꿈은 JSON 문자열 안의 `\n`으로 이스케이프되므로 JSON 한 줄이 이벤트 하나다. 요청 본문은 두 형식 모두 `application/json`이다.

```ndjson
{"type":"progress","stage":"INITIAL_SEARCH","message":"관련 조문을 검색하고 있습니다."}
{"type":"info","info":"## 고정 근거\n\n"}
{"type":"answer_delta","delta":"확인한 조문에 따르면 "}
```

이벤트 종류·근거 교체·검증 전 임시 답변·완료/오류·heartbeat·인증/CSRF·저장 계약은 두 형식이 같다. 프론트 예제는 `format: "ndjson"`을 지정하면 기본 경로와 Accept를 함께 선택하고 줄 단위 파서로 수신한다. 기존 SSE 클라이언트는 기본 `format: "sse"`를 사용한다.

모든 경로에 Access 쿠키 인증과 `X-XSRF-TOKEN` 헤더가 필요하다. `/api/auth/csrf`에서 JSON 토큰을 받아 사용한다. 형식에 맞는 `Accept`, `Content-Type: application/json`, `credentials: include`로 **POST fetch**를 사용한다. 브라우저 `EventSource`는 POST·CSRF 헤더를 지원하지 않으므로 사용하지 않는다. 기존 후속 JSON 질문 API는 유지한다.

각 SSE 프레임은 `event: 종류`와 `data: JSON`이며 빈 줄로 구분한다. `data.type`은 event 이름과 같다.

| 이벤트 | 표시 방법 |
| --- | --- |
| `progress` | `stage`, `message`를 진행 상태로 표시. 퍼센트나 내부 추론을 생성하지 않는다. |
| `info` | `info` 마크다운으로 **이전 근거 전체를 교체**. 실제 읽고 채택한 근거가 생길 때마다 전송한다. 중복 추가하지 않는다. |
| `answer_delta` | `delta`를 임시 답변에 이어 붙인다. 모델이 생성하는 실제 조각이며 완료한 답변을 잘라 보내는 방식이 아니다. |
| `completed` | `response.answer`와 `response.info`로 최종 교체. 저장형에서는 `room.id`를 사용한다. 여기까지 받아야 확정 답변이다. |
| `error` | 공개 `code`, `message`를 표시하고 임시 답변을 폐기한다. 이미 확인한 info는 참고 자료로 남길 수 있으나 완료된 상담으로 표시하면 안 된다. |
| `heartbeat` | 10초마다 연결 유지. 화면에는 표시하지 않는다. |

중간 `info`와 `answer_delta`에는 내부 `[[UUID]]` 대신 `[근거 1]` 같은 표시용 번호를 사용한다. 인용 ID가 여러 모델 조각으로 나뉘어도 중간 UUID 일부가 노출되지 않도록 요청별 상태를 유지한다. 알려지지 않은 인용이나 미완성 인용은 중간 화면에 노출하지 않고, 원문을 별도 보존해 최종 인용 검증에서 실패시킨다. 같은 요청 안에서 근거 번호는 유지하며 제외된 번호를 다른 근거에 재사용하지 않는다. `completed.response`는 기존 UUID 인용 계약을 유지하므로 기존 최종 답변 인용 렌더러를 그대로 사용한다.

후속 NDJSON은 본인 방의 이전 대화·고정 법령 근거를 복원하고, 동일한 Advisor와 판례 동의 도구 규칙으로 모델 스트림을 호출한다. 법령 수집·임베딩을 다시 실행하지 않는다. 판례 검색 동의는 이번 요청의 `allowCaseSearch=true`로만 허용하고, 동의가 없으면 먼저 동의를 묻는다. 판례 도구 사용 결과를 포함한 최종 원문 인용을 검증한 뒤 기존 트랜잭션으로 질문·답변·새 인용 판례만 저장한다. 동의 요청 시 서버가 구성한 근거 포함 질문으로 최종 답변을 교체할 수 있으므로 항상 completed 값을 최종으로 사용한다.

후속 완료 시 `messageResult.caseSearchRequested`를 보고 승인 UI를 표시하고, 사용자가 승인한 다음 요청에만 `allowCaseSearch=true`를 보낸다. 후속 응답에는 `room`이 없고 `response.info`는 빈 문자열이므로 기존 상담방·근거를 지우지 않는다. 새 법령 근거를 수집하는 API가 아니다. 시작 전 타인 방 접근 403·없는 방 404·질문 검증 400은 `application/json` 오류이며, 스트림을 시작한 뒤의 오류는 `error` 이벤트다.

승인된 판례 검색은 기본 본문 범위에서 시작하고 LLM이 사건명/참조법령 범위를 선택할 수 있다. 법률용어 변환 후 선택 범위의 전체 페이지를 예산 안에서 수집하고 본문 리랭크를 수행한다. FOLLOW_UP_PREPARATION/CASE_QUERY_PLANNING/CASE_SEARCH_TITLE/CASE_SEARCH_LAW/CASE_SEARCH_FACT/CASE_BODY_REVIEW/CASE_RERANK 진행 이벤트와 같은 traceId의 판례 처리 로그를 제공한다. 수집·리랭크 제한과 최초/후속 로그 필터링은 [판례 검색 흐름](case-retrieval-reranking.md)을 참고한다.

```javascript
await streamInitialAnswer({
  url: `/api/chat-rooms/${roomId}/messages/ndjson`, format: "ndjson",
  body: { content: question, allowCaseSearch: false }, csrfToken, signal, onEvent,
});
```

상담방 근거 목록·상세 API의 `sourceUrl`도 기존 `/법령?lsiSeq=...` 링크를 `lsInfoP.do?lsiSeq=...`로 교정한다. 저장한 원문·근거 스냅샷은 수정하지 않는다.

`progress`의 대표 단계는 QUESTION_PLANNING(쟁점 분석), INITIAL_SEARCH(조문 검색), DECIDING(본문 읽기·충분성 판단), SEARCH_AGAIN(재검색 검토), LAW_SEARCH(추가 법령 탐색), CANDIDATE_REVIEW(후보 검토), LAW_DETAIL(원문 수집), LAW_INDEX/LAW_INDEX_REUSE(색인 준비), SEARCH_AFTER_INDEX(확보 후 재검색), ANSWER_GENERATION(답변 작성), SAVING(제목 정리·저장)이다. 필요 없는 단계는 보내지 않으며 추가 수집 시 단계가 반복될 수 있다. stage는 서버 실행 단계이며 비공개 모델 사고 과정이 아니다.

검토 라운드마다 현재 채택한 근거 목록을 보내고 답변 작성 직전에 최종 목록으로 교체한다. 검색 후보만 발견했거나 본문을 읽지 않은 ID는 채택 근거로 내보내지 않는다. 출처 버전 변경이나 최종 선택으로 근거가 제외될 수 있어 info는 추가 전용 배열이 아닌 교체용 스냅샷이다. 출처 링크는 서버가 구성하며 모델이 URL을 생성하지 않는다.

스트림의 마지막에 인용·답변 길이를 검증한다. 미확인 쟁점 문구는 최종 `response.answer`에 포함되므로 delta를 합친 문자열 대신 완료 결과를 최종 값으로 사용해야 한다. 저장형은 검증 후 기존 트랜잭션으로 질문·답변·근거를 원자적으로 저장한다. 작성 중에는 방을 만들지 않는다. 연결 종료는 모델 스트림 구독을 취소하고 수집 단계도 다음 진행 알림에서 중단한다. 이미 실행 중인 동기 HTTP 호출은 해당 호출의 타임아웃까지 걸릴 수 있다. 커밋 이후 완료 프레임 수신 전에 연결이 끊기면 저장은 유지된다. 자동 재연결·POST 재시도는 중복 방을 만들 수 있어 제공하지 않는다.

스트림 시작 전 인증/CSRF·질문 검증 실패는 기존 HTTP 401/403/400 계약을 따른다. 시작 후 수집 실패·근거 부족·모델 실패·인용 실패·동시 실행 제한은 HTTP 200 `error` 이벤트로 전달한다. 프록시는 응답 버퍼링을 끄고 장시간 연결을 허용해야 한다. 서버는 `Cache-Control: no-cache, no-transform`, `X-Accel-Buffering: no`를 전송한다.

JWT 필터는 검증한 인증 정보를 `RequestAttributeSecurityContextRepository`에 명시적으로 저장한다. 같은 HTTP 요청의 ASYNC/ERROR 재처리에서만 복원하며 새 요청이나 HTTP 세션으로 인증을 넘기지 않는다. 따라서 스트림 완료 후 비동기 디스패치에서도 최초 요청의 인증을 유지한다. ASYNC 요청의 인증 검사를 일괄 생략하지 않는다. 관련 동작은 [Spring Security의 요청 속성 기반 인증 저장](https://docs.spring.io/spring-security/reference/servlet/authentication/persistence.html)을 따른다.

`APP_CHAT_STREAM_MAX_CONCURRENT`(기본 4), `APP_CHAT_STREAM_WRITE_THREADS`(기본 4), `APP_CHAT_STREAM_TIMEOUT`(기본 20m)은 환경변수로 조정한다. timeout은 작업 스트림의 유휴 제한이며 MVC 응답 수명은 설정 시간+30초다. 기존 법령 수집·모델 HTTP 예산은 별도로 유지한다.

로그에는 스트림 ID·SSE/NDJSON 형식·단계·완료/오류·연결 종료 사유·경과 시간·답변 조각 수를 남긴다. 질문·답변·근거 본문·키는 기록하지 않는다. 기존 JSON API 호출도 경로와 시작/완료를 기록해 스트리밍 경로 호출과 구분한다. `completed` 로그는 서버의 검증·저장 및 이벤트 생성 완료이며 사용자의 화면 수신 확인을 뜻하지 않는다.

프론트에서 사용할 SSE/NDJSON 파서 예시는 [initial-answer-stream.js](examples/initial-answer-stream.js)를 참고한다. 모델 스트림은 [Spring AI ChatClient의 stream().content()](https://docs.spring.io/spring-ai/reference/api/chatclient.html)를 사용한다. [Spring MVC 비동기 처리](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-async.html)는 두 스트리밍 미디어 형식을 항목 단위로 전송한다.

후속 상담은 준비와 최종 작성 호출을 분리한다. 준비 단계의 모델 텍스트는 보내지 않고 실제 처리 progress와 확인한 info만 전송한다. 도구 없는 최종 작성 단계에서만 answer_delta를 보내므로 도구 호출 전 초안이 사라졌다가 다시 작성되는 현상을 방지한다. 내부 상태 코드는 최종 본문과 스트림 조각에서 자연어로 치환한다.
