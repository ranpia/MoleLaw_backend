# 최초 상담 실행

기준일: 2026-10-23. 기존 POST /api/chat-rooms에 질문을 보내면 검색부터 답변·방 저장까지 동기로 진행한다. 단계를 보기 위해 ETL 테스트 API를 미리 호출할 필요는 없다.

## 준비와 요청

루트 .env에 DB·Redis·OAuth·JWT·CHAT_ENCRYPTION_KEY·OpenAI·OC_KEY를 준비한다. 기본 QDRANT 경로는 APP_LAW_VECTOR_ENABLED=true와 MySQL·Qdrant·HTTP 임베딩 서버가 필요하다. 서비스용 threaded KURE의 실행은 [Docker 가이드](../docker/README.md)를 따른다. 코드의 임베딩 기본 주소는 11436이며 서비스용 11437을 사용할 때 APP_LAW_VECTOR_EMBEDDING_BASE_URL을 명시한다.

local 서버와 Swagger를 실행하고 GET /api/auth/csrf에서 받은 쿠키·JSON 토큰을 유지한다. 가입·로그인 후 인증 쿠키 또는 Bearer와 X-XSRF-TOKEN 헤더로 요청한다.

```http
POST /api/chat-rooms
Content-Type: application/json
X-XSRF-TOKEN: <csrf-token>

{"content":"공항 근처에 15층 건물을 지으려는데 고려해야 할 법령이 필요해"}
```

새 모델·.env 변경은 실행 중 서버를 재시작해야 적용된다. 운영에서는 local/test 프로필을 사용하지 않는다.

## Qdrant 실행 순서

1. 질문을 유형·답변 범위·검색 질의로 분류한다.
2. 기존 COMPLETE 색인에서 서로 다른 조문 최대 15개를 검색한다.
3. 충분성 Client가 답변·재검색·법령 수집·부족을 제안한다.
4. 수집이면 검색 조건의 모든 페이지를 리랭크하고 기준 통과 후보 중 상위 2개를 보관한다.
5. 법령 한 건의 상세·MySQL 원문·ETL·Qdrant 저장 검증을 마친 뒤 즉시 재검색·충분성 판단으로 돌아간다.
6. ANSWER_READY가 지정한 실제 근거로 답변하고 UUID 인용·출처를 검증한다.
7. 성공한 방·근거 스냅샷·암호화 USER/BOT을 원자적으로 저장한다. INFO는 스냅샷에서 재구성한다.

후속 질문은 POST /api/chat-rooms/{roomId}/messages로 보내며 Advisor와 최초 고정 근거만 사용한다. 새로운 검색·법령 수집·색인은 실행하지 않는다.

## 한도와 실패

| 항목 | 현재 값·동작 |
| --- | --- |
| 추가 행동 | 최대 4회와 최종 판단 |
| 일반 처리 | 3분·검색·수집 HTTP 128회·상세 법령 6건 |
| 임베딩 | 일반 타이머에서 제외, 누적 900초·개별 HTTP 읽기 기본 120초 |
| 페이지 | 일반 법령 검색은 전체 검토. 완료 전 예산 종료 시 해당 조건의 부분 후보 색인 금지 |
| 후보 | 70점 이상 상위 2개. 제목 기반 초기값이며 법적 적합성 보장 아님 |
| 근거 부족 | 422 EVIDENCE_INSUFFICIENT |
| 검색 장애·비활성 | 503 RETRIEVAL_UNAVAILABLE |
| 임베딩 과부하 | 503 RETRIEVAL_BUSY |
| 모델·계약 오류 | 기존 GPT 오류 경로. 정상 빈 검색 결과와 구분 |

전체 페이지 검토로 첫 응답이 길어질 수 있다. timeout은 경계 검사이며 원격 추론의 즉시 취소·연결 종료 후 중단·서버 재시작 복구를 보장하지 않는다. 실제 usage 비용 상한과 SSE는 미구현이다.

## 진단과 검증

executionId로 QUESTION_PLANNING·INITIAL_SEARCH·DECIDING·LAW_SEARCH·CANDIDATE_REVIEW·RERANK_COMPLETE·LAW_DETAIL·LAW_INDEX·SEARCH_AFTER_INDEX를 연결한다. 검색 후보 수는 관련성이나 실제 전달 근거 수와 다르다. local 판단 진단의 reason·missingIssues·visibleEvidence로 부족 사유를 확인한다.

ETL 저장 누락은 Law index verification과 POINT_COUNT expected/stored로 확인한다. 법령 API 날짜·파서·임베딩 실패와 충분성 부족을 구분한다. 로그에 토큰·상담 원문·원시 모델 출력을 추가하지 않는다.

모의 통합은 initialConsultationSimulationTest, 실제 임베딩은 embeddingLiveTest로 구분한다. scripts/Test-InitialConsultation.ps1은 실제 API·모델을 사용하는 별도 검증이다. 최신 실행 범위는 [검증 현황](validation-status.md)을 따른다.

AI_SEARCH는 별도 경로이며 위 전체 페이지 리랭크·ETL을 사용하지 않는다. 기본 전환과 실 API 재시도는 [AI Search 비교](ai-search-comparison.md)의 보류 상태를 따른다.

## 분리된 HTTP 호출 예산

검색·법령 API·Qdrant 검색은 기존 최초 상담 128회 한도를 사용한다. 모델 정보 조회·질문/문서 임베딩은 APP_LAW_VECTOR_EMBEDDING_MAX_CALLS(기본 1024회), 컬렉션 준비·적재·저장 검증·완료 세대 재검증은 APP_LAW_VECTOR_INDEXING_MAX_CALLS(기본 1024회)를 각각 실행 전체에서 누적한다. 법령·배치마다 초기화하지 않으며 한 실행에서 설정을 높이거나 바꾸지 못한다. 임베딩의 별도 누적 900초·일반 처리 시간·행동 한도는 유지한다.

모델 revision 조회는 법령 색인 세션의 첫 배치에서 수행하고 이후 배치는 생략한다. 새 법령·새 재색인 세션 또는 배치 실패 후 재시도에서는 다시 검증한다. 각 배치의 응답 모델명·차원·유한값·정규화 검증과 최종 Qdrant 저장 검증은 유지한다. 오류 로그 call budgets의 retrievalUsed/embeddingUsed/indexUsed와 exhausted로 소진 구분을 확인한다. calls는 세 종류의 총 HTTP 횟수이며 LLM 토큰 예산과 별개다.

## 읽은 근거로 충분성 판단

최초 충분성 판단은 후보 전체 목록→본문 읽기 선택→MySQL 원문 확인→실제 읽은 본문 입력으로 변경했다. 기존 2500토큰은 판단 기본 JSON의 상한이며 본문을 그 안에서 한 조문만 남기는 방식은 사용하지 않는다. 후보 목록 4000·본문 누적 12000·결합 판단 입력 20000토큰의 추정 상한과 최대 2회 읽기 선택을 적용한다. [현재 계약과 제한](evidence-reading.md)을 따른다.
