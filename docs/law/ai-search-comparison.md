# 조문 API Tool과 직접 구현한 RAG 비교

## 현재 상태

`aiSearch` Tool·비교 API·최초 상담 경로 전환 코드를 준비했다. 기본 상담 경로는 **QDRANT**로 유지한다. 실제 API 재시도는 보류한다.

2026-10-23 동일 인증값의 대조 요청에서 일반 `law` 검색은 HTTP 200, `aiSearch`는 HTTP 404였다. 사용자는 `aiRltLs`도 404라고 확인했다. 권한·서비스 상태·제공자 라우팅 중 어느 원인인지는 확인되지 않았다. 정상 응답을 확보하지 못했으므로 실제 조문 검색 품질과 최종 응답 JSON 구조는 아직 검증하지 못했다.

공식 가이드: [aiSearch](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=aiSearchGuide), [aiRltLs](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=aiRltLsGuide).

## Tool 계약

`law.service.tool.LawAiSearchToolService.search(context, request)`는 서버가 직접 호출한다. 후속 상담 Client에 자동 Tool로 등록하지 않는다. Follow-up 권한 컨텍스트는 원격 조회를 거절한다.

- `target=aiSearch`, `search=0`(법령 **조문**)으로 고정한다. 기존 목록 API의 `search=1/2`(제목/본문)와 다른 매개변수다.
- 검색어 최대 Unicode 2,000자, 페이지 1~10,000, 페이지 크기 1~15. 별표·서식·행정규칙은 이번 구현 범위가 아니다.
- 법령·조문 ID, 시행일, 본문, 페이지 정보를 검증한다. 가지 조문은 `제34조의2`처럼 구분한다. 제공자가 보내는 URL 대신 검증한 법령 일련번호로 공식 출처 URL을 만든다.
- HTML·오류 문서·알 수 없는 응답 형태·HTTP 장애는 빈 결과로 간주하지 않는다. 장애 시 자동 재호출이나 다른 경로로의 대체는 없다.
- `LAW_AI_SEARCH` 근거와 서버 생성 UUID를 사용하며, 최초 성공 답변의 본문·출처 메타데이터를 기존 불변 스냅샷에 저장한다. 후속 답변은 이 스냅샷과 Advisor만 사용한다.

문서에는 응답 필드가 있지만 정상 응답 envelope를 실제 확보하지 못했다. 파서는 `AiSearch`/`aiSearch` 또는 직접 필드 형태와 문서 필드·표준 `totalCnt`/`law` 형태를 제한적으로 지원한다. 모의 응답은 **가상 계약 fixture**이며 실서비스 응답을 캡처한 자료가 아니다. 나중에 정상 응답을 확보해 구조를 대조해야 한다.

## 로컬 검색 비교

`local` 프로필에서 다음 API를 제공한다. 인증 쿠키 또는 Bearer와 CSRF가 필요하다. `/api/auth/csrf` JSON 토큰을 `X-XSRF-TOKEN` 헤더에 전달한다. 운영 기본 프로필에는 노출하지 않는다.

```http
POST /api/local/law-retrieval/ai-search
Content-Type: application/json

{"query":"공항 높이 제한","page":1,"display":15}
```

```http
POST /api/local/law-retrieval/compare
Content-Type: application/json

{"query":"공항 높이 제한"}
```

비교는 같은 검색어를 변환 없이 양쪽에 순차 전달한다. 결과에 검색 경로별 `status`, `elapsedMillis`, `returnedCount`, `hits`와 공통 법령 ID가 나온다. `AVAILABLE`, `EMPTY`, `UNAVAILABLE`, `BUSY`를 구분하며 API 장애나 Qdrant 미설정도 HTTP 200 진단 결과에 표시한다. 입력 오류는 400, 인증·CSRF 오류는 401/403이다. API 장애 때 `providerTotalCount`를 0으로 조작하지 않는다.

이 API는 LLM·ETL·답변 생성·상담방 저장을 하지 않는다. Qdrant에는 질문 임베딩만 요청한다. 원격 API와 로컬 색인의 모집단이 다르므로 결과 수·공통 법령 수가 정확도 점수는 아니다. 제목·본문의 관련성은 같은 평가 질문과 정답 조문으로 별도 평가해야 한다.

## 최초 상담 경로 선택

루트 `.env`에서 선택한 뒤 Spring 서버를 재시작한다.

```dotenv
APP_LAW_RETRIEVAL_BACKEND=QDRANT
# 실서비스 정상 응답 확인 후 선택:
# APP_LAW_RETRIEVAL_BACKEND=AI_SEARCH
```

`QDRANT`는 기존 수집·후보 심사·ETL 경로를 유지한다. `AI_SEARCH`는 질문 분류 → 조문 API → 충분성 판단 → 답변·인용 검증 → 스냅샷 저장 순서이며 Qdrant나 임베딩 서버 없이 사용할 수 있다. `SEARCH_AGAIN`은 새 질의 또는 같은 질의의 다음 페이지를 조회한다. 질의별 최대 3페이지, 추가 행동 최대 4회, 원격 호출 최대 24회, 처리 시간 3분을 적용한다. 이 경로에서 `COLLECT_LAW`와 로컬 ETL은 지원하지 않는다. 모델이 지원하지 않는 행동을 선택하면 실패하며 임의로 변환하지 않는다.

API만 사용할 경우 `APP_LAW_VECTOR_ENABLED=false`로 둘 수 있다. 비교에서 Qdrant도 실행하려면 기존 MySQL·Qdrant·임베딩 설정과 완료 색인을 준비한다. `APP_LAW_AI_SEARCH_BASE_URL`은 기본 `https://www.law.go.kr/DRF`이며 로컬 모의 서버 연결 시에만 변경한다. 인증값 `OC_KEY`는 Git에서 제외한 `.env`에서 관리한다.

## 로컬 모의 검증

실제 제공자 대신 Postman HTTP 모의 서버와 H2를 사용한다. 모델도 모킹하며 외부 모델 호출 비용이 없다.

```powershell
postman simulation run postman/simulations/law-ai-search.sim.yaml --port 14038 --output ndjson
# 다른 터미널:
$env:LAW_AI_SEARCH_SIMULATION_URL='http://127.0.0.1:14038'
.\gradlew.bat aiLawSearchSimulationTest
```

요청의 `target/search`, 특수문자 인코딩, 페이지, 정상 빈 결과·404·잘못된 JSON, Qdrant 없는 최초 답변·인용·저장·복원과 API 실패 시 미저장을 검증한다. 실제 제공자의 검색 품질을 검증하는 테스트는 아니다.

## 판단·후속 작업

실 API 재시도 일정과 공급자 채택은 [사용자 판단 D08](../decisions/README.md), 작업 순서는 [P07](../plans/README.md)에 둡니다. 일반 법령 검색의 전체 페이지 리랭크는 이 AI Search 경로에 적용하지 않습니다.
