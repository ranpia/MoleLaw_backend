# 개발 완료 현황과 다음 작업

정리일: 2026-10-21. 서비스 기준은 `dev`의 `859def2`, 검색 실험 기준은 `experiment/legal-embedding-evaluation`의 `2178f06`이다. 현재 코드·Git 이력·기존 검증 기록을 대조한 문서이며 이번에 테스트·모델 평가·DB 상태 조회를 재실행하지 않았다.

## 1. dev에 구현된 기능

| 영역 | 완료 범위 | 근거·제한 |
| --- | --- | --- |
| 프로젝트 기반 | Java 17·Boot 3.5·Spring AI 1.0.9, 도메인 패키지 분리 | `auth/user/chat/law/common`, 기존 채팅·계정 API 유지 |
| Access 인증 | User.id 기반 버전 3 JWT, 모든 프로필 최대 5분, 서명·용도·issuer/audience 검증 | Redis Access 등록/조회/폐기 제거. 사용자 존재·권한은 MySQL에서 확인 |
| Refresh | 난수 토큰·Redis 해시·절대 7일, Lua 원자 회전·재사용 탐지·family/전체 폐기 | HttpOnly 쿠키 전용, 응답 JSON에서 제거. 로그아웃 후 기존 Access는 최대 5분 유효 |
| 브라우저 보안·문서 | CORS 허용 목록·CSRF JSON→헤더, 공통 인증 쿠키, Swagger/OpenAPI 설명·예시 | 문서 노출/Swagger 자동 CSRF는 local과 문서 검증용 test에 한정 |
| MySQL 기반 | Flyway V1 업무 테이블 7개, 기본/local JPA validate, User IDENTITY | 빈 DB 시작. 기존 데이터 이관·MySQL Refresh 테이블은 현재 범위에 없음 |
| 법령·판례 Tool | 목록/상세 네 내부 Tool, 입력·후보 ID·페이지·호출 예산·중복 검사 | 정확명·ID·부처·종류·상태·정렬·날짜 필터. 후속 Tool 실행 거절 |
| 원문 보존 | `legal_source`의 UUID·JSON·해시·출처·수집 시각·버전 식별 정보 저장/복원 | 원문 스냅샷이며 상담방별 근거 스냅샷이나 ETL 완료는 아님 |
| LLM 호출·역할 분리 | 최초/후속 답변 서비스·근거 수집·공통 프롬프트/출처 표시 분리, 초기/후속 Client 격리 | 수집 판단 LLM 반복·실제 답변 품질 평가는 후속 |
| 실행·검증 기반 | MySQL/Qdrant/Redis Compose, CPU Ollama 테스트 환경, 독립 MySQL/Redis 통합 테스트 | 컨테이너 실행과 실제 서비스 RAG 전환 완료는 구분 |

주요 이력: MySQL `b92c4c5`, 사용자 ID `dc1f2eb`, 법령 테스트 API 응답 `7ac6cb4`, Refresh `0d3775c`, stateless Access `83ca4df`/병합 `61a4823`. 최신 `859def2`는 추가 수집·재검색 **계획 문서** 커밋이다.

## 2. 실험 브랜치에서 완료한 작업

다음 결과는 `2178f06`의 기록이며 dev에 병합하거나 서비스 설정에 적용하지 않았다. dev 작업 폴더에 남은 미추적 `evaluation/` 캐시를 구현 완료의 근거로 사용하지 않는다.

| 실험 | 완료·결론 |
| --- | --- |
| 모델·청크 비교 | EmbeddingGemma/BGE-M3/KURE-v1/Qwen3 0.6B × 300/500/700자 비교. 초기 탐색 기준선은 KURE-v1·1024차원·300자/64자 중첩·정제 질문 |
| 확대 검증 | 주 법령 30개·270질문·10,999청크의 원본/정제 결과 보존. 원문을 보며 만든 표적 질문이라는 한계 기록 |
| 독립 질문 검증 | 독립 작성 100문항 평가. 코퍼스 내 36문항의 정제 Hit@5 30.56%, Complete@5 8.33%로 서비스 충분성은 입증하지 못함 |
| 질의·청크 후속 비교 | 법률 쟁점 변환, 500/700자, Recall@50 후보 회수 확인. Hit@5·Complete@5 공동 우선, 임계값 사후 탐색은 비활성 이력으로 보존 |
| 파일·Qdrant 정리 | 목적별 입력/검토/보고서 배치, 263개 고정 파일의 이동 전후 해시 일치 기록. 물리 컬렉션 29개·113,982포인트 보존, 별칭 29개 정합성 검증 기록 |

KURE는 실험 기준 모델이며 서비스 모델 확정이 아니다. `serviceReady=false`를 유지하고 Multi-Query·BM25·RRF 비교는 후속 계획으로 구분한다. Qdrant 수치·검증은 기록 당시 값이며 현재 DB를 다시 측정한 값이 아니다.

실험 출처는 dev에 없는 경로이므로 로컬 실험 커밋에서 조회한다. 해당 브랜치 병합이나 원격 게시를 완료한 것으로 표시하지 않는다.

```powershell
git show 2178f06:evaluation/legal-retrieval/experiments/summary.md
git show 2178f06:evaluation/legal-retrieval/experiments/model-comparison/review/model-selection.md
git show 2178f06:evaluation/legal-retrieval/experiments/infrastructure/qdrant/organization-verification.md
```

## 3. 이번에 추가한 독립 벡터 Tool

`LawVectorizationToolService`는 기존 법령 조회 Tool이 같은 실행에서 저장한 원문을 조문별로 분할하고 Python 임베딩 API를 호출해 서비스 전용 Qdrant 컬렉션에 적재한다. `LawRagSearchToolService`는 같은 모델로 질문을 임베딩하고 서로 다른 조문을 최대 15개 반환한다. 기본 비활성화이며 설정·계약은 [법령 벡터 Tool](law-vector-tools.md)을 따른다.

기존 상담 흐름·검색 서비스 연결, Spring AI ETL 인터페이스 적용, 작업 상태와 완료 버전 공개는 아직 구현하지 않았다. 부분 적재 실패 시 이미 적재한 조문은 검색될 수 있다. 모델 품질 평가는 별도다.

## 4. 아직 완료하지 않은 항목

- 구현한 독립 벡터 Tool을 Spring AI ETL Reader→Transformer→Writer와 실제 상담 흐름에 연결. 기존 `searchLaws/getLawDetail`과 MySQL 원문 참조를 재사용한다.
- 법령 버전·수집/색인 작업 상태·중단 재개, 근거 충분성 판단에 따른 동적 수집/재검색 조정.
- Qdrant 서비스 검색 전환. 현재 `LawSimilarityService`는 MySQL 임베딩 전체 조회·직접 코사인 계산을 사용한다.
- 요청 중복/동시성 제어·법령 버전/시행일 보강·인용 내용의 의미 검증. 최초 근거 고정·ID 인용 검증·성공 턴 저장은 구현했다.
- 근거 수집 판단 계약과 수집·재검색 반복. 최초·후속 답변 서비스 분리와 전용 Client 연결, ID 형식/소속 검증은 구현했다.
- 대화·근거의 토큰 예산과 DB 페이지 조회 최적화. 후속 상담의 Advisor 기록 복원과 최초 근거 전달은 구현했다.
- 단일 LLM 답변 품질 평가, 멀티 에이전트 오케스트레이터와 역할별 모델/추론 선정.
- SerpApi fallback은 검토 중이다. 운영 Redis 복구·호출 제한·브라우저/운영 전환 검증도 별도 작업이다.

## 5. 후속 LLM 작업 계획

구성 기반 구현: 공통 prototype Builder와 `EvidenceChatClientConfig`·`AnswerChatClientConfig`를 추가했다. 같은 ChatModel을 공유하되 역할별 Client·프롬프트·옵션을 분리했다. 기존 `AiChatService`는 Primary 호환 Client를 유지한다. [설정·미연결 범위](role-chat-clients.md)를 따른다.

Advisor 연결: 후속 Client만 MessageChatMemoryAdvisor를 사용한다. 기존 암호화 DB 기록에서 최초 USER/BOT과 최근 기록을 복원하고 INFO는 제외한다. 소유권을 재검증하며 후속 상담 성공 쌍만 별도 트랜잭션으로 저장한다. 최초 답변은 메모리 없는 전용 Client로 전환했다. 근거 수집 판단 반복은 미연결이다.

근거 연결: 방별 최초 근거 사본과 ID를 Flyway V2 테이블에 고정한다. 후속 Client에는 대화와 함께 이 근거를 전달한다. 최초·후속 인용은 방 근거 ID·형식·누락·모델 URL을 검증하며, 스냅샷 없는 기존 방은 처리하지 않는다. 최초 생성은 방·근거·메시지를 한 번에 커밋한다. [근거 계약](room-evidence.md)을 따른다.

이번 검증: 일반 테스트 220개 중 219개 통과·선택 Postman 1개 건너뜀, 격리 MySQL V2 통합 2개 통과. 최종 INFO 렌더링 정리 후 최초 근거·OpenAPI 테스트도 통과했다. 실제 모델 API는 호출하지 않았다.

답변 분리·정리 검증: `test bootJar` 통과(일반 226개 중 225개 통과·선택 Postman 1개 건너뜀). 최초·후속 모델 호출을 별도 서비스로 옮기고 근거 수집·프롬프트·서버 출처 표시를 분리했다. 미사용 DTO 3개와 조회 메서드·일반 텍스트 호출·미사용 필드/인자를 제거했다. 마지막 RAG 생성자·Swagger 샘플 정리도 해당 기능·OpenAPI 테스트와 JAR 재생성으로 검증했다.

서비스 단순화: 최초·후속 생성 서비스는 `LegalAnswerService`로, 저장 서비스는 `ChatTurnService`로 통합했다. `ChatService`의 의존성은 7개에서 5개로 줄이고 소유권 검사와 모델 호출·검증 중복을 제거했다. 각 저장 공개 메서드의 트랜잭션, 초기/후속 Client 분리와 근거 검증은 유지한다. 최종 `test bootJar`는 231개 중 230개 통과·선택 Postman 1개 건너뜀, JAR 생성 통과다. H2에서 후속 두 메시지 저장과 중간 실패 롤백, 비인증 조회·삭제와 타인 방 삭제 차단을 추가 검증했다.

독립 벡터 Tool을 기존 상담 흐름에 연결하는 시점은 별도 결정한다. LLM 역할 분리 전후 HTTP 응답 계약을 유지하며 다음 항목은 후속 계획이다.

1. **계약:** 질문·근거·대화 입력과 답변·인용 ID·근거 부족 여부를 정의한다.
2. **수집 Client 연결:** 미연결 근거 수집 Client의 구조화 판단과 Tool 실행 서비스를 연결한다. 최초·후속 답변 Client 연결은 완료했다.
3. **역할 경계 유지:** `ChatService`(조정·권한), `LegalAnswerService`(생성·검증), `ChatTurnService`(저장)를 중심으로 정리했다. 근거 수집·메모리·스냅샷·포맷팅·인용 검증은 별도 책임을 유지하며 같은 흐름의 중복 서비스는 만들지 않는다.
4. **기준선:** 고정 근거로 단일 LLM의 정상 답변·근거 부족·잘못된 인용·실패를 검증한다. 실제 검색 품질과 답변 품질을 분리해 평가한다.
5. **대화:** 구현된 Advisor·근거 스냅샷·성공 턴 저장에 토큰 예산과 동시 요청 제어를 보강한다. 멀티 에이전트는 이후 단계다.

## 6. 검증 이력의 해석

[Access](stateless-access.md)·[Refresh](refresh-rotation.md) 구현 문서에는 2026-10-14 일반 148개·독립 Redis 11개·MySQL 2개·JAR 통과가 기록돼 있다. [MySQL 가이드](mysql-setup.md)의 95개는 초기화 구현 당시 기록이다. 서로 더하거나 이번 작업의 테스트 결과로 표현하지 않는다.

이번 작업은 독립 벡터 Tool 구현과 테스트를 포함한다. 실제 모델 품질 검증과 기존 실험 컬렉션 변경·자료 이동·병합은 수행하지 않는다. 기존 실험 자료와 캐시는 보존한다.

2026-10-21 최종 검증: `test qdrantIntegrationTest` 통과. 일반 테스트 169개 중 168개 통과·선택 Postman 테스트 1개 건너뜀, 격리 Qdrant 통합 1개 통과. 선택 Postman 테스트는 별도 실행에서 1개 통과하고 실제 tags/embed 요청을 확인했다. 실제 모델 추론 품질은 검증하지 않았다.

추가 실서버 검증: `embeddingLiveTest`에서 실제 KURE 1024차원 임베딩과 격리 Qdrant를 사용해 검증용 20조문·40청크의 적재 및 중복 없는 상위 15개 검색을 통과했다. Python 서버의 Content-Length 필수 계약에 맞춰 클라이언트를 수정했다. 원문 저장소는 테스트 대역이며 실제 법령 검색 정확도는 평가하지 않았다. [검증 조건](law-vector-tools.md)을 따른다.
