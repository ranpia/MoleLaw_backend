# 법령 ETL과 로컬 테스트 API

2026-10-22: 동기 ETL의 Reader → Transformer → Writer 연결과 수동 재처리를 구현했다. 운영용 영속 작업 큐·자동 재개까지 완료했다는 의미는 아니다.

## 구성

- `LegalApiToolService`: 페이지 검색·후보 검증·상세 수집·MySQL 원문 저장.
- `LawSourceDocumentReader`: DB에서 복원한 불변 원문을 Spring AI Document로 구성.
- `LawArticleDocumentTransformer`: 기존 조문·항·호·목 파서 재사용. Unicode 기준 청킹과 조문 전체 본문 보존.
- `LawQdrantDocumentWriter`: 기존 로컬 임베딩 HTTP 서버를 배치 호출하고 Qdrant REST로 기록. `VectorStore.add()`로 다시 임베딩하지 않는다.
- `LawVectorizationToolService`: 원문/설정별 완료 색인 재사용, WRITING → COMPLETE/FAILED 상태 관리. 기존 최초 질문 수집기도 같은 파이프라인을 사용한다.

임베딩 응답 검증·Qdrant 쓰기·저장 결과 재조회 검증까지 성공한 후에만 완료 세대를 검색에 공개한다. 실패하면 이전 완료 세대를 유지하며 부분 적재는 검색에서 제외한다. 재시도는 새 세대로 전체 원문을 재처리한다. 동일 완료 원문·설정은 저장 포인트 검증도 통과한 경우에만 재임베딩 없이 재사용한다. 외부 호출 중 DB 트랜잭션은 유지하지 않는다.

## 실행과 요청

질문 하나로 수집부터 첫 답변·상담방 저장까지 진행하려면 [최초 상담 실행](initial-consultation.md)의 `POST /api/chat-rooms`를 사용한다. 아래 세 API는 단계별 진단용이며 먼저 호출할 필요가 없다.

루트 `.env`에 기존 MySQL·OAuth·암호화·법령 API 설정과 `SPRING_PROFILES_ACTIVE=local`, `APP_LAW_VECTOR_ENABLED=true`를 설정한다. 임베딩 서버·MySQL·Qdrant를 실행한 뒤 `.\gradlew.bat bootRun`으로 서버를 켠다.

API는 `local & !test`와 벡터 기능 활성화 조건에서만 등록된다. 운영에서는 `local`을 사용하지 않는다. 모든 요청은 인증 쿠키 또는 Bearer와 CSRF 쿠키/헤더가 필요하다. `GET /api/auth/csrf`의 JSON 토큰을 `X-XSRF-TOKEN`으로 전송한다. Swagger UI는 `/swagger-ui.html`의 **법령 ETL 테스트** 태그에서 확인한다. 태그가 없으면 루트 `.env`의 `SPRING_PROFILES_ACTIVE=local`과 `APP_LAW_VECTOR_ENABLED=true`를 확인하고 서버를 재시작한다. 벡터 기능을 끄면 컨트롤러와 문서가 함께 제외된다.

| POST 경로 | JSON 예시 | 결과 |
| --- | --- | --- |
| `/api/local/law-etl/index` | `{"query":"민법","startPage":1,"maxPages":3}` | 검색·수집·색인 결과와 다음 페이지 |
| `/api/local/law-etl/reindex` | `{"referenceId":"저장된 원문 UUID"}` | API 재호출 없이 재색인/완료 세대 재사용 |
| `/api/local/law-etl/search` | `{"query":"신의성실 원칙"}` | 서로 다른 조문 최대 15개. 답변 생성 없음 |

`index`는 페이지당 후보 1개, 요청당 최대 3페이지를 처리한다. `filters` 생략 시 완전 일치하여 민법 검색에 난민법을 적재하지 않는다. 다른 검색이 필요하면 `filters`에 `{"exactName":false}`, 법령 ID·종류·상태·날짜 등을 지정한다. 필터로 비어도 다음 페이지가 있으면 계속 탐색한다. 응답 `nextPage`가 있으면 같은 검색 조건의 `startPage`로 전달하고 null이면 종료한다. API 최대 10,000페이지에서도 종료한다.

정상 빈 결과는 200과 빈 목록이다. 입력 오류 400, 미인증 401, CSRF 오류 403, 외부 장애·한도 초과 503 `RETRIEVAL_UNAVAILABLE`, 임베딩 대기열 초과 503 `RETRIEVAL_BUSY`다. 부분 완료 후 오류가 발생할 수 있으므로 같은 조건으로 수동 재시도한다. 수집 원문 자체는 불변 스냅샷으로 다시 저장될 수 있으나 완료 벡터는 재사용한다.

로컬 사전 수집의 일반 처리 시간은 15분·HTTP 2,048회 한도다. 최초 상담은 일반 처리 3분·검색/수집 128회다. 두 실행 모두 임베딩 시간은 일반 처리 타이머에서 제외하고 별도 누적 900초 한도를 적용한다. 호출 횟수도 검색/수집·임베딩·색인으로 분리한다. 대기열·질문 임베딩·법령 배치를 모두 합산하며 배치마다 초기화하지 않는다. `APP_LAW_VECTOR_EMBEDDING_TIMEOUT_SECONDS`와 개별 HTTP 읽기 제한 `APP_LAW_VECTOR_EMBEDDING_READ_TIMEOUT_MILLIS`는 루트 .env에서 관리한다. 시간 초과 결과와 미완료 세대는 공개하지 않는다. 진행 중인 원격 추론의 즉시 취소나 브라우저 연결 종료 시 중단은 보장하지 않는다. SSE·자동 재시도·자동 전체 수집은 추가하지 않았다.

## 검증과 남은 운영 작업

- 일반 검증: `.\gradlew.bat test bootJar`.
- 실제 CPU 모델과 격리 Qdrant: `.\gradlew.bat embeddingLiveTest`.
- Postman 임베딩 계약 모의 서버: `postman simulation run postman/simulations/law-embedding.sim.yaml --port 14036 --output ndjson` 실행 후 `$env:LAW_VECTOR_SIMULATION_URL='http://127.0.0.1:14036'; .\gradlew.bat lawEtlSimulationTest`.

Postman 모의 계약은 저장소의 기존 Ollama 호환 임베딩 계약을 재사용한다. 이 검증은 실제 법령 API나 검색 정확도를 입증하지 않는다.

2026-10-22 검증 결과: 일반 테스트 298개 중 실패 0·선택 HTTP 테스트 2개 건너뜀, bootJar 성공. 별도 Postman 검증 2개와 실제 KURE/격리 Qdrant 검증 1개를 통과했다. simulation 로그에서 실제 GET /api/tags와 POST /api/embed의 HTTP 200을 확인했다. 실제 KURE는 1,024차원으로 가상 조문 20개→청크 40개→서로 다른 조문 15개 검색을 확인했다. 테스트용 컨트롤러는 입력·재색인·검색 응답, 인증·CSRF, OpenAPI와 local 등록 조건을 검증했다. 이 과정의 원문 저장소는 모킹했고 실제 법령 API·유료 LLM은 호출하지 않았다. 테스트용 simulation 서버와 격리 컨테이너는 종료했다.

영속 페이지/배치 재개·서버 중단 WRITING 회수·분산 실행 중복 방지·실패/구세대 점의 물리 정리는 후속 운영 범위다. 재색인은 배치 중간 재개가 아니다. 실제 법령 API부터 MySQL·모델·Qdrant·최종 답변까지 한 번에 연결한 법률 품질 평가는 별도로 수행한다.

2026-10-22 Swagger 노출 검증: 루트 .env에서 local 프로필과 벡터 기능을 켜고 빌드한 JAR을 실행해 /v3/api-docs에 index·reindex·search 세 POST 경로가 포함되는 것을 확인했다. Swagger UI HTML은 HTTP 200으로 응답했다. 브라우저 화면 조작이나 실제 ETL 요청은 이번 문서 검증에서 실행하지 않았다. 한글 태그와 검색 조건·성공/빈 응답 예시를 문서 회귀 테스트로 확인했다.

## 완료 검증과 이후 실행

새 색인은 쓰기 완료 응답만 믿지 않고 해당 generation의 정확한 포인트 수, 예상 청크 ID 전체, 조문 unit_id, 원문 UUID·해시·시행일·법령 ID·모델 revision·설정 fingerprint, 실제 벡터 차원·유한값·정규화를 재조회한다. 검증을 통과해야 DB를 COMPLETE로 변경한다. 불일치는 INDEX_VERIFICATION_FAILED로 기록하고 해당 시도는 FAILED 처리하여 검색에서 제외한다. 숫자·출처 메타데이터 검증이며 조문의 법적 관련성이나 적용 시점을 보장하지 않는다.

기존 COMPLETE 색인도 재사용 전에 동일 검증을 한다. 포인트 누락·불일치가 확인되면 해당 기록을 FAILED로 전환하고 저장된 원문을 새 generation으로 한 번 재처리한다. 검증 통신 장애는 누락으로 취급하지 않으며 재임베딩을 시작하지 않는다. 새 시도도 실패하면 종료하며 무한 재시도하지 않는다. 삭제나 변조된 점을 자동 복구할 수 있지만 이전 세대의 물리 정리는 하지 않는다.

최초 상담은 법령 한 건을 검증·공개한 직후 재검색하고 LLM 충분성 판단으로 돌아간다. 충분하면 답변하고 부족할 때 다음 수집 행동을 선택한다. 서버의 호출·시간·행동 예산은 그대로 적용되며 검증 HTTP는 별도 색인 호출 예산에 포함된다. 로그의 Law index verification expectedChunks/verified와 POINT_COUNT expected/stored로 누락을 확인한다.

프로세스 재시작·클라이언트 연결 종료 후 작업을 복원하는 영속 작업 큐나 SSE는 이번 변경 범위가 아니다. 실패한 저장 원문은 기존 reindex API로 다시 실행할 수 있다. 저장 검증은 Qdrant [정확한 포인트 수 조회](https://api.qdrant.tech/api-reference/points/count-points)와 [포인트 재조회](https://api.qdrant.tech/api-reference/points/get-points)를 사용한다.

현재 실행 계약과 별도로 남은 작업은 [P04·P05](plans/README.md), 비용·응답 정책은 [사용자 판단](decisions/README.md)에 모읍니다. 위 로컬 index의 최대 3페이지는 진단 API 계약이며 최초 상담의 전체 페이지 리랭크와 다릅니다.

## 분리된 HTTP 호출 예산

검색·법령 API·Qdrant 검색은 기존 최초 상담 128회 한도를 사용한다. 모델 정보 조회·질문/문서 임베딩은 APP_LAW_VECTOR_EMBEDDING_MAX_CALLS(기본 1024회), 컬렉션 준비·적재·저장 검증·완료 세대 재검증은 APP_LAW_VECTOR_INDEXING_MAX_CALLS(기본 1024회)를 각각 실행 전체에서 누적한다. 법령·배치마다 초기화하지 않으며 한 실행에서 설정을 높이거나 바꾸지 못한다. 임베딩의 별도 누적 900초·일반 처리 시간·행동 한도는 유지한다.

모델 revision 조회는 법령 색인 세션의 첫 배치에서 수행하고 이후 배치는 생략한다. 새 법령·새 재색인 세션 또는 배치 실패 후 재시도에서는 다시 검증한다. 각 배치의 응답 모델명·차원·유한값·정규화 검증과 최종 Qdrant 저장 검증은 유지한다. 오류 로그 call budgets의 retrievalUsed/embeddingUsed/indexUsed와 exhausted로 소진 구분을 확인한다. calls는 세 종류의 총 HTTP 횟수이며 LLM 토큰 예산과 별개다.
