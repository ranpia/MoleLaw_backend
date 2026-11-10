# 역할별 ChatClient와 모델

코드 대조 기준: `b0b37d8` (2026-10-30 검토). 실제 호출 기록은 [답변 생성 결과](../testing/answer-generation-results.md)와 [전체 RAG 첫 실호출](../testing/full-rag-first-live.md)을 함께 읽는다. 일부 실호출 성공을 모델 전반의 품질 검증으로 확대하지 않는다.

## 역할과 옵션

기본 QDRANT 최초 수집은 lawToolChatClient에 요청별 법령 도구 4개를 등록한다. 근거 모델/완료 토큰 설정을 공유하지만 GPT-5.4 Chat Completions의 함수 도구 제한 때문에 추론은 none으로 명시한다. 내부 자동 도구 실행은 끄고 서버가 왕복/도구 한도를 검사한다. 구조화 판단용 evidenceCollectionChatClient와 evidenceReadChatClient는 AI_SEARCH 진단 경로에 유지한다. [법령 도구 계약](../law/law-retrieval-tools.md)을 따른다.

| Bean | 기본 모델 | effort | 완료 토큰 한도 |
| --- | --- | --- | ---: |
| questionPlanningChatClient | gpt-5.4-mini | low | 4096 |
| lawCandidateChatClient | gpt-5.4-mini | low | 8192 |
| evidenceReadChatClient | gpt-5.4-mini | low | 4096 |
| evidenceCollectionChatClient | gpt-5.4 | medium | 12288 |
| lawToolChatClient | gpt-5.4 | none | 12288 |
| legalAnswerChatClient | gpt-5.4-mini | medium | 16384 |
| followUpAnswerChatClient | 최초 답변 Client 설정 복제 | none | 16384 |
| followUpCasePlanningChatClient | gpt-5.4-mini | low | 2048 |
| initialQuestionSuggestionChatClient | gpt-5.4-mini | low | 2048 |
| caseQueryPlanningChatClient | gpt-5.4-mini | low | 2048 |
| caseRerankChatClient | gpt-5.4-mini | low | 4096 |

최초 상담 추천 Client는 초안만 받아 질문 방향을 생성하며 Advisor·Tool·검색·저장을 사용하지 않는다. 원문과 추천 방향을 조합하는 작업 및 길이 검사는 서버가 수행한다. [추천 질문 계약](../chat/initial-question-suggestions.md)을 따른다.

후속 판례 실행계획 Client는 Advisor 없이 최신 사용자 메시지로만 검색 여부를 판단한다. 방 법령은 사용자가 선택했을 때 검색 맥락으로 전달하며 법령 근거 없이도 판례 검색이 가능하다. 요약·정리·재설명은 기존 근거로 답하고 과거 검색 요청을 승계하지 않는다. 서버는 검색 계획이 있을 때만 동의를 요청하거나 실제 검색·본문 읽기를 실행한다. 이후 모델에는 기존 판례 읽기만 허용하고 검색·동의 도구는 제공하지 않는다. 답변 작성 Client의 대화 Advisor는 유지한다. 설정 접두사는 `APP_AI_FOLLOW_UP_PLAN_*`다. 1차 테스트 이후 리팩토링에서 YAML 매핑을 Bean과 같은 `app.ai.follow-up-plan`으로 맞췄다.

반복되는 분류·법령명 평가와 근거 기반 문장 생성은 mini, 수집 전환·답변 가능 여부 판단은 상위 모델에 배정했다. 이는 현재 배정 이유이며 비교 평가로 입증한 성능 우위가 아니다. 키워드 추출의 Primary chatClient는 OPENAI_CHAT_MODEL(기본 gpt-5.4-mini)과 OPENAI_CHAT_REASONING_EFFORT(기본 none)를 사용한다. 키워드 요청의 temperature=0.3과 호환되도록 이 역할의 추론은 비활성화하며, 별도 역할 Client의 low/medium 설정은 유지한다.

공통 prototype Builder는 같은 ChatModel·API 연결·인증을 공유한다. EvidenceChatClientConfig와 AnswerChatClientConfig가 Builder를 clone하고 역할별 OpenAiChatOptions에서 모델·추론 수준을 재정의한다. Bean 생성 시 외부 API를 호출하지 않는다.

구조화 호출의 본문 읽기·판례 질의·판례 후보 선정·후속 실행계획은 역할별 옵션 Bean을 Client와 planner가 공유한다. 요청마다 옵션 복사본에 동적 JSON schema만 결합해 모델·effort·완료 한도를 보존하며, 허용 ID enum과 요청별 범위를 유지한다. 1차 전체 RAG에서 관측한 READ effort=none은 수정 전 기록이며 이후 실호출 재검증과 구분한다.

## 환경변수

.env의 APP_AI_{QUESTION,CANDIDATE,READ,EVIDENCE,ANSWER}_MODEL, APP_AI_*_REASONING_EFFORT, APP_AI_*_MAX_COMPLETION_TOKENS로 값을 바꾼다. 추천 질문·판례 질의·판례 리랭크는 각각 QUESTION_SUGGESTION·CASE_QUERY·CASE_RERANK 접두사를 쓴다. `ReasoningOptions`는 none/low/medium/high를 허용하며 후속 답변과 법령 도구 전용 클라이언트는 none으로 고정한다. 전체 환경변수 예제는 [.env.example](../../.env.example)을 따른다.

완료 토큰 한도는 내부 추론과 실제 출력의 합계다. 예약 비용·고정 사용량·요청 전체 예산이 아니다. APP_AI_EVIDENCE_MAX_INPUT_TOKENS=8000은 질문·검색 이력 등 판단 기본 JSON의 상한이다. 후보 목록·본문·결합 입력은 [본문 읽기](../law/evidence-reading.md)의 별도 예산을 적용한다. usage 기반 비용 정산은 미구현이다.

추론 요청에는 reasoning_effort와 max_completion_tokens를 사용한다. 기존 역할별 temperature·max-tokens는 사용하지 않으며 Spring AI 1.0.9의 암묵적 전역 temperature=0.7도 모델 생성 전에 제거한다. 본문 읽기 선택은 요청별 JSON Schema Structured Outputs로 미시도 후보 ID enum과 최대 8개를 제한한다. 서버 ID·중복·크기 검증도 유지한다. 다른 역할의 스키마는 프롬프트/서버 검증 방식이다.

APP_AI_READ_TIMEOUT_MS 기본값은 120000이다. 수집기의 전체 시간·호출 한도는 그대로이며 호출 경계에서 확인한다. 진행 중인 모델 HTTP를 전체 deadline에 맞춰 즉시 취소하는 기능은 없다.

## Advisor·저장·권한

최초 답변에는 대화 메모리를 붙이지 않는다. 후속 Client만 MessageChatMemoryAdvisor를 사용하고 서버가 확인한 userId:roomId를 대화 ID로 전달한다. PersistedChatMemory도 소유권을 재검증한다.

DB의 최초 USER/BOT과 최근 기록을 기본 최대 20개 복원하며 INFO는 제외한다. 메시지 수 제한은 토큰 예산을 보장하지 않는다. 현재 질문은 모델 입력에 한 번만 넣고, Advisor의 add/clear가 원본 DB 기록을 쓰거나 지우지 않는다.

최초·후속 모두 서버의 고정 근거를 별도로 전달한다. LegalAnswerService가 호출·인용 검증을, ChatTurnService가 성공 턴 저장을 담당한다. 최초는 방·근거·메시지, 후속은 USER/BOT·동의 표시·새로 인용한 판례 참조를 원자적으로 저장한다. 모델 실패·빈 출력·잘못된 인용은 성공 턴으로 확정하지 않는다.

## 검색·판단 Client

분류·법령 후보 리랭크·충분성 판단에는 대화 Advisor와 자동 실행 Tool을 붙이지 않는다. 서버가 검증한 모델 제안을 실행한다. 법령 후보는 페이지별 전원 평가 후 전체 페이지의 상위 후보를 선택한다. 상세 기준은 [질문 라우팅](../law/question-routing.md), 상태 계약은 [판단 계약](../law/evidence-decision.md)에서 관리한다.

자동 모델 승격·병렬 토론·영속 작업 큐는 구현하지 않았다. 최초 답변은 SSE와 NDJSON, 후속 답변은 NDJSON 진행 이벤트를 제공한다. 후속 법령 수집·ETL은 금지한다. 새 판례 검색은 요청별 사용자 동의를 검사하며 방 법령과의 일치를 검색 자격으로 요구하지 않는다. [후속 상담](../chat/follow-up-consultation.md)과 [스트림 계약](../chat/initial-answer-stream.md)을 따른다.

## 검증과 출처

RoleChatClientConfigTest가 모델·effort·완료 토큰 격리, 기본 temperature 제거, 설정 오류와 Advisor 경계를 검증한다. 실제 모델의 옵션 수락·usage·정확도는 별도다. [검증 현황](../validation/validation-status.md)을 따른다.

공식 사양: [GPT-5.4 mini](https://developers.openai.com/api/docs/models/gpt-5.4-mini), [GPT-5.4](https://developers.openai.com/api/docs/models/gpt-5.4). 구현은 저장소에 고정한 Spring AI 1.0.9 API를 사용한다.

## 읽은 근거로 충분성 판단

최초 충분성 판단은 후보 전체 목록→본문 읽기 선택→MySQL 원문 확인→실제 읽은 본문 입력으로 변경했다. 질문 분석을 포함한 8000토큰은 판단 기본 JSON의 상한이며 본문을 그 안에서 한 조문만 남기는 방식은 사용하지 않는다. 후보 목록 8000·본문 누적 12000·결합 판단 입력 32000토큰의 추정 상한과 최대 2회 읽기 선택을 적용한다. [현재 계약과 제한](../law/evidence-reading.md)을 따른다.

## 후속 판례 처리와 인용 교정 (2026-10-27)

일반 질문은 ANSWER_FROM_ROOM으로 판례 준비·본문 읽기를 생략한다. 기존 판결 자체의 검토 요청만 READ_ROOM_CASES로 로컬 읽기를 실행하고, 새 판례 조회 요청만 SEARCH_CASES로 동의·검색을 실행한다. 인용 표기 검증 실패는 대화 메모리·검색 도구 없는 기존 최초 답변 Client에 동일 확인 근거와 초안을 전달해 1회 교정한다. 교정 후에도 기존 인용 검증을 통과해야 저장한다. 실시간 근거 카드와 삭제는 상담 API 계약을 따른다.
