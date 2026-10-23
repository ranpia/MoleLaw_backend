# 패키지·DTO·엔드포인트 정리

기준: 2026-10-22, dev. 역할이 섞인 TestController와 UserController, 법령 도구 폴더, 서비스 내부 공유 DTO를 정리했다. DB 스키마·LLM 판단·검색 정책은 이 정리에서 변경하지 않았다.

## 패키지 소유권

- auth/controller: 가입·로그인·로그아웃·Refresh·CSRF. user/controller: 내 정보·계정 삭제·비밀번호·닉네임.
- law/controller: 법령·판례·키워드 API. chat/controller: 상담방·메시지와 방을 저장하지 않는 법률 답변 API.
- law/service/tool: 서버가 호출하는 도구와 실행 권한·예산 컨텍스트. 원문·벡터 HTTP 구현을 이 폴더에 섞지 않는다.
- law/service/source, retrieval, search, analysis: 각각 원문/색인 기록, 근거 수집/판단, 검색, 키워드 추출.
- law/util: 외부 I/O·트랜잭션이 없는 JSON 파싱, 부처 코드 매핑, 조문 청킹, 로컬 임베딩 부하 제한. 보조 클래스는 서비스에 섞지 않고 한곳에 둔다.
- law/client/openlaw, vector: 외부 HTTP 경계. CaseSearchService는 단일 구현에 불필요한 인터페이스를 두지 않는다.

## DTO 구분

HTTP 입력·출력은 각 도메인의 dto/request·response에 둔다. LawDto/PrecedentInfo는 LawDetailResponse/PrecedentSummaryResponse로, 질문·답변 타입은 LawQuestionRequest, CreateChatRoomRequest, CreateMessageRequest, ChatRoomCreationResponse, LegalAnswerResponse로 의미를 명확히 했다. KeywordExtractionResponse는 키워드 분석 결과다.

공유 내부 계약은 law/dto/evidence, retrieval, tool, vector와 chat/dto/answer에 모았다. EvidenceItem/Provenance는 법령 검색이 만들고 상담이 소비하므로 law가 소유한다. LawSearchEvidence/LawIndexResult를 도구 클래스의 중첩 타입에서 분리했다. 페이지 후보·청크처럼 구현에만 필요한 타입은 해당 구현에 유지한다. 생성/후속 메시지 요청은 서로 다른 작업 계약이라 별도 DTO를 유지한다.

컨트롤러 안의 CSRF 문서 전용 DTO는 실제 CsrfResponse로 교체했다. 인증 제네릭 응답의 구체 OpenAPI 스키마는 AuthApiSchemas와 UserApiResponse로 옮겼다. Java 클래스 이름을 바꿔도 기존 OpenAPI component 이름은 @Schema 별칭으로 유지한다.

## 경로 변경표

기존 경로 별칭은 제공하지 않는다. 프론트·클라이언트의 호출 경로를 함께 변경해야 한다. GET 검색의 파라미터와 응답 본문은 유지한다.

| 이전 | 현재 |
| --- | --- |
| POST /api/auth/reissue | POST /api/auth/refresh |
| GET·DELETE /api/auth/me | GET·DELETE /api/users/me |
| PATCH /api/auth/password | PATCH /api/users/me/password |
| PATCH /api/auth/nickname | PATCH /api/users/me/nickname |
| POST /api/chat-rooms/first-message | POST /api/chat-rooms |
| GET /api/chat-rooms/{roomId} | GET /api/chat-rooms/{roomId}/messages |
| GET /api/test/lawsearch | GET /api/laws |
| POST /api/test/law/keyword | POST /api/laws/search |
| POST /api/test/keywords | POST /api/law-keywords |
| GET /api/test/cases | GET /api/precedents |
| GET /api/test/cases/{id} | GET /api/precedents/{id} |
| POST /api/test/answer | POST /api/legal-answers |

법령 AI 검색 POST /api/laws/search의 본문은 이전 content 대신 `{"query":"보증금을 돌려받지 못했습니다."}`다. 키워드·무저장 답변도 query를 사용한다. 상담방 생성·후속 메시지는 기존 content를 유지한다.

가입·로그인·로그아웃·CSRF 경로와 후속 메시지 POST·방 삭제는 유지한다. Refresh 쿠키 Path=/api/auth, Access 인증·소유권 검사·CSRF·CORS 정책도 유지한다. Swagger customizer와 SecurityConfig/JwtAuthenticationFilter의 공개 경로를 새 AuthController·refresh에 맞췄다. 운영 Swagger를 활성화하지 않는다.

과거 기준선·보안 감사 문서의 옛 경로는 해당 시점 기록이다. 현재 클라이언트 계약은 이 문서와 local OpenAPI를 우선한다.
## 검증

2026-10-22 추가: `LawEtlTestController`는 `local & !test` 및 벡터 활성화 조건에서만 `POST /api/local/law-etl/index`, `/reindex`, `/search`를 제공한다. 공통 20개 API 계약은 유지하며 로컬 검증 경로의 인증·CSRF·요청/응답·OpenAPI는 별도 테스트로 확인한다. [ETL 사용법](law-etl-local.md)을 따른다.

일반 테스트 283개(실패 0, 선택 Postman 실행 1개 건너뜀)와 bootJar를 통과했다. OpenApiDocumentationTest는 새 경로·HTTP 메서드 20개, 인증/CSRF 요구·응답 예시·schema를 확인한다. 법령 AI 검색은 옛 content 본문을 거절하고 query 본문을 처리하는 HTTP 회귀를 추가했다. 사용자 ID·방 소유권·Refresh 회전과 실제 CSRF 쿠키 부트스트랩 회귀도 포함된다. 패키지 이동 후 HTTP 임베딩 클라이언트는 별도 로컬 Postman simulation에서 검증한다. 실제 OpenLaw/OAuth/유료 LLM은 호출하지 않았다.

최종 추가 검증: Swagger 문구 갱신 후 OpenAPI 테스트와 선택 Postman 테스트 총 5 개를 통과했다. simulation 로그에서 실제 클라이언트의 GET /api/tags·POST /api/embed 및 HTTP 200을 확인했고 테스트 서버를 종료했다.

2026-10-23: local 전용 POST /api/local/law-retrieval/ai-search·/compare가 추가됐습니다. 기존 공개 20개 경로와 분리된 진단 경로이며 인증·CSRF를 유지합니다. [AI Search 비교](ai-search-comparison.md)를 따릅니다.
