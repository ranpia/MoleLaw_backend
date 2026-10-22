# 법령 ETL과 로컬 테스트 API

2026-10-22: 동기 ETL의 Reader → Transformer → Writer 연결과 수동 재처리를 구현했다. 운영용 영속 작업 큐·자동 재개까지 완료했다는 의미는 아니다.

## 구성

- `LegalApiToolService`: 페이지 검색·후보 검증·상세 수집·MySQL 원문 저장.
- `LawSourceDocumentReader`: DB에서 복원한 불변 원문을 Spring AI Document로 구성.
- `LawArticleDocumentTransformer`: 기존 조문·항·호·목 파서 재사용. Unicode 기준 청킹과 조문 전체 본문 보존.
- `LawQdrantDocumentWriter`: 기존 로컬 임베딩 HTTP 서버를 배치 호출하고 Qdrant REST로 기록. `VectorStore.add()`로 다시 임베딩하지 않는다.
- `LawVectorizationToolService`: 원문/설정별 완료 색인 재사용, WRITING → COMPLETE/FAILED 상태 관리. 기존 최초 질문 수집기도 같은 파이프라인을 사용한다.

임베딩 전체 검증과 모든 Qdrant 쓰기가 성공한 후에만 완료 세대를 검색에 공개한다. 실패하면 이전 완료 세대를 유지하며 부분 적재는 검색에서 제외한다. 재시도는 새 세대로 전체 원문을 재처리한다. 동일 완료 원문·설정은 임베딩하지 않는다. 외부 호출 중 DB 트랜잭션은 유지하지 않는다.

## 실행과 요청

루트 `.env`에 기존 MySQL·OAuth·암호화·법령 API 설정과 `SPRING_PROFILES_ACTIVE=local`, `APP_LAW_VECTOR_ENABLED=true`를 설정한다. 임베딩 서버·MySQL·Qdrant를 실행한 뒤 `.\gradlew.bat bootRun`으로 서버를 켠다.

API는 `local & !test`와 벡터 기능 활성화 조건에서만 등록된다. 운영에서는 `local`을 사용하지 않는다. 모든 요청은 인증 쿠키 또는 Bearer와 CSRF 쿠키/헤더가 필요하다. `GET /api/auth/csrf`의 JSON 토큰을 `X-XSRF-TOKEN`으로 전송한다. Swagger UI는 `/swagger-ui.html`의 **Local law ETL** 태그에서 확인한다.

| POST 경로 | JSON 예시 | 결과 |
| --- | --- | --- |
| `/api/local/law-etl/index` | `{"query":"민법","startPage":1,"maxPages":3}` | 검색·수집·색인 결과와 다음 페이지 |
| `/api/local/law-etl/reindex` | `{"referenceId":"저장된 원문 UUID"}` | API 재호출 없이 재색인/완료 세대 재사용 |
| `/api/local/law-etl/search` | `{"query":"신의성실 원칙"}` | 서로 다른 조문 최대 15개. 답변 생성 없음 |

`index`는 페이지당 후보 1개, 요청당 최대 3페이지를 처리한다. `filters` 생략 시 완전 일치하여 민법 검색에 난민법을 적재하지 않는다. 다른 검색이 필요하면 `filters`에 `{"exactName":false}`, 법령 ID·종류·상태·날짜 등을 지정한다. 필터로 비어도 다음 페이지가 있으면 계속 탐색한다. 응답 `nextPage`가 있으면 같은 검색 조건의 `startPage`로 전달하고 null이면 종료한다. API 최대 10,000페이지에서도 종료한다.

정상 빈 결과는 200과 빈 목록이다. 입력 오류 400, 미인증 401, CSRF 오류 403, 외부 장애·한도 초과 503 `RETRIEVAL_UNAVAILABLE`, 임베딩 대기열 초과 503 `RETRIEVAL_BUSY`다. 부분 완료 후 오류가 발생할 수 있으므로 같은 조건으로 수동 재시도한다. 수집 원문 자체는 불변 스냅샷으로 다시 저장될 수 있으나 완료 벡터는 재사용한다.

로컬 사전 수집은 15분·HTTP 2,048회 한도로 실행하며 각 요청 전후에 deadline을 검사한다. 진행 중인 HTTP 호출의 즉시 취소나 브라우저 연결 종료 시 중단은 보장하지 않는다. 최초 상담의 기존 3분·128회 예산은 그대로다. SSE·자동 재시도·자동 전체 수집은 추가하지 않았다.

## 검증과 남은 운영 작업

- 일반 검증: `.\gradlew.bat test bootJar`.
- 실제 CPU 모델과 격리 Qdrant: `.\gradlew.bat embeddingLiveTest`.
- Postman 임베딩 계약 모의 서버: `postman simulation run postman/simulations/law-embedding.sim.yaml --port 14036 --output ndjson` 실행 후 `$env:LAW_VECTOR_SIMULATION_URL='http://127.0.0.1:14036'; .\gradlew.bat lawEtlSimulationTest`.

Postman 모의 계약은 저장소의 기존 Ollama 호환 임베딩 계약을 재사용한다. 이 검증은 실제 법령 API나 검색 정확도를 입증하지 않는다.

2026-10-22 검증 결과: 일반 테스트 298개 중 실패 0·선택 HTTP 테스트 2개 건너뜀, bootJar 성공. 별도 Postman 검증 2개와 실제 KURE/격리 Qdrant 검증 1개를 통과했다. simulation 로그에서 실제 GET /api/tags와 POST /api/embed의 HTTP 200을 확인했다. 실제 KURE는 1,024차원으로 가상 조문 20개→청크 40개→서로 다른 조문 15개 검색을 확인했다. 테스트용 컨트롤러는 입력·재색인·검색 응답, 인증·CSRF, OpenAPI와 local 등록 조건을 검증했다. 이 과정의 원문 저장소는 모킹했고 실제 법령 API·유료 LLM은 호출하지 않았다. 테스트용 simulation 서버와 격리 컨테이너는 종료했다.

영속 페이지/배치 재개·서버 중단 WRITING 회수·분산 실행 중복 방지·실패/구세대 점의 물리 정리는 후속 운영 범위다. 재색인은 배치 중간 재개가 아니다. 실제 법령 API부터 MySQL·모델·Qdrant·최종 답변까지 한 번에 연결한 법률 품질 평가는 별도로 수행한다.
