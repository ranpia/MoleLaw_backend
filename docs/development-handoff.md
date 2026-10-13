# API 기능 개발 인수인계

기준일: 2026-10-13. 구현된 내부 Tool과 앞으로 연결할 상담 기능을 구분한다. 전체 방향은 [재구축 요약](backend-rebuild-plan.md)을 따른다.

## 현재 개발 기준

패키지는 `auth`, `user`, `chat`, `law`, `common`으로 구성하며 [도메인 구조 스킬](../.agents/skills/domain-structure/SKILL.md)을 따른다. 법령 Tool 구현은 `law.service.tool`, 인증 구현은 `auth.security`와 `auth.oauth`에 있다.

Access jti 등록·TTL·매 요청 확인·전달된 Access 폐기와 저장소 장애 503 처리를 구현했다. 로그인 세션·MySQL Refresh·전체 폐기는 미구현이며 [인증 실행 계획](plans/auth-security.md)의 구현 절을 따른다.

후속 기능은 기능 브랜치에서 개발하고 `dev`에서 통합·테스트한 뒤 검증한 변경을 `main`에 반영한다. CI·수동 배포 조건과 실행 환경은 [프로젝트 안내](../README.md)와 [Docker 가이드](../docker/README.md)를 따른다. 과거 브랜치 생성·병합 이력은 Git 이력에서 확인한다.

## 구현된 법령 Tool 범위

- `LegalApiToolService`: 법령·판례 목록/상세 네 내부 Tool. 공개 HTTP API·MCP·자동 tool-calling은 추가하지 않았다.
- `SearchRequest`·`LawSearchFilters`: 제목/본문·부처·페이지·법령ID·종류·상태·정확명·정렬·시행일/공포일 범위. 기존 생성자와 법령 fallback 유지.
- `LegalToolContext`: 실행별 호출/시간 예산, 중복 페이지·후보 소진, 상세 ID 허용 목록, 후속 Tool 실행 거부. 서버가 인증·소유권 확인 후 생성해야 한다.
- `LegalSource`·Repository·Service: UUID·JSON 원문·해시·조회 시점·출처·MST/판례ID 스냅샷 저장/복원. 상담방별 근거 연결과는 별개다.
- `openLawLiveTest`: 실제 법령·판례 API 계약 검사. 일반 테스트와 분리하고 키/원문을 출력하지 않는다.

상세 입력/반환/오류 계약과 예제는 [API Tool 개발 문서](legal-api-tools.md)를 참고한다. 정확명은 API 문자열 검색과 서버 제목 검사를 함께 적용하므로 `민법` 요청에 `난민법`을 채택하지 않는다.

## 실행과 스키마 적용

1. Java 17과 Gradle Wrapper를 사용하고 `OC_KEY`는 환경변수 또는 Git 제외 `.env`로 제공한다.
2. 스키마 자동 변경이 꺼진 DB에는 [001-legal-source.sql](sql/001-legal-source.sql)을 먼저 적용한다. 백업·기존 테이블 유무를 확인하고 SQL을 반복 실행하지 않는다. 자동 Flyway 실행은 아직 없다.
3. `LegalApiToolService`를 서버 내부에서 주입해 사용한다. 사용자/모델이 실행 목적·예산·소유권 문맥을 만들게 하지 않는다.

```powershell
.\gradlew.bat test
.\gradlew.bat bootJar
.\gradlew.bat openLawLiveTest
```

일반 테스트는 H2·모킹을 사용하며 실제 모델/API 키가 필요 없다. 실호출 테스트는 외부 API 5회를 사용하고 원문은 테스트 메모리에만 저장한다. 실제 MySQL 저장 검증은 별도 수행해야 한다.

## 검증 결과와 제한

2026-10-13 법령 Tool 개발 당시 일반 테스트 56개 및 실제 API 테스트를 통과했고 `test bootJar`로 패키징을 확인했다. 이는 이후 인증·구조 변경 전체의 최신 검증 결과가 아니다. 실호출에서 현행·부처·종류 조건의 API 후보 2건을 정확명으로 걸러 민법 1건, 법령ID 001706·MST 284415·시행일 20260317을 확인했다. 검색 결과와 버전은 시점에 따라 바뀐다.

이 결과는 법률 적용·검색 품질 평가나 상담 통합 완료를 의미하지 않는다. `contentAvailable`도 본문 존재 여부만 나타낸다. 기존 상담 서비스는 아직 새 Tool을 호출하지 않는다.

## 다음 담당 작업

1. 실제 MySQL 스키마/원문 저장 통합과 명시적 마이그레이션 도입.
2. MySQL 원문 Reader → 구조 보존 Transformer → Qdrant writer, Ollama 임베딩 비교/선정.
3. 실행 중단·페이지 재개 이력 영속화, 전역 동시성·남은 deadline에 맞춘 HTTP 제한.
4. 인증/방 소유권·최초 질문 오케스트레이터 연결, 상담방 근거 스냅샷과 Advisor 성공 턴 저장.
5. 근거 관련성·시행일/사건 기준일·후속 검색 금지 평가. SerpApi는 [검토안](plans/search-fallback.md)이며 미도입.

CPU Ollama 테스트 이미지는 [별도 실행 가이드](../docker/ollama/README.md)를 따른다. 이미지 구동과 Spring 임베딩 전환을 혼동하지 않는다.
