# 커밋·코드와 문서 대조

기준일: 2026-10-30. 기준 커밋: `b0b37d8`. 현재 소스의 동작과 날짜별 실행 증거를 구분해 `docs/` 전체를 검토했다. 변경이 필요 없는 문서와 보관 기록은 유지했다.

## 검토 범위

| 문서 | 대조 근거와 반영 내용 |
| --- | --- |
| [프로젝트](../project/README.md)·[전체 안내](../README.md) | 커밋 이력·현재 브랜치, 상담 서비스·추천 API·관측 도구. 현재 기능과 검증 범위를 갱신 |
| [인증](../auth/README.md) | SecurityConfig·JWT/OAuth·Refresh 저장소·사용자 서비스. 비동기 인증 보존·OAuth 프로필·Bearer 우선순위 확인 |
| [상담](../chat/README.md)·[법령](../law/README.md)·[예제](../examples/initial-answer-stream.js) | 컨트롤러·DTO·상담/수집/검색/인용 코드. 스트림·동의·본문 읽기·고정 색인 시험 경계 반영 |
| [구조](../architecture/README.md) | 실제 패키지·컨트롤러·역할별 ChatClient·YAML. 신규 API와 설정 경로 제한 반영 |
| [DB](../database/README.md) | 엔티티·Flyway V1~V8·통합 테스트. 9개 업무 테이블과 근거 제외 기록 반영 |
| [테스트](../testing/README.md) | scripts/load-testing·docker/loadtest·Gradle·CI. 현재 도구와 당시 준비/실측 기록 구분 |
| [계획](../plans/README.md)·[결정](../decisions/README.md) | 현재 구현과 남은 운영 정책 대조. SSE 후순위·전체 생성 미검증 등 오래된 설명 정정 |
| [검증](README.md)·[보관](../archive/README.md) | 날짜별 증거·이동 안내·수동 SQL 검토. 과거 수치·실패 기록 보존, 현재 migration 안내 정정 |

## 주요 커밋과 현재 상태

- `eecacd7`·`b502668`: 후속 판례/근거/스트림 보완과 최초 추천 질문 API.
- `4790c86`~`f8e2422`: 격리 부하 환경·Prometheus/Grafana·조회/DB/Refresh/임베딩/리랭커/혼합 시험 도구와 기록.
- `189e8cc`·`09f4770`: 실제 writer 시험과 스트림 대역. 최초 인용 실패와 후속 성공 기록을 함께 유지한다.
- `e7ed862`·`1f0041b`: 고정 색인 전체 RAG 시험 준비·공통 예산 게이트웨이·실제 1상담 결과. 운영 요청별 예산·동적 수집 검증과 구분한다.
- `6f64e33`·`2354626`: 비동기 취소/슬롯 반환 테스트 대기와 후속 진행 이벤트·완료 순서 보완.
- `79f2435`·`c99d9a2`: 실패 테스트 보고서 보존과 MySQL V8 기대값 수정. 정의·수정 사실을 특정 CI 실행 성공으로 해석하지 않는다.
- `b0b37d8`: PR #12 병합. 로컬 dev·main이 해당 커밋을 포함함을 확인했다. 배포 실행 여부는 확인하지 않았다.

## 발견한 설정 제한

최초 문서 대조 당시 후속 검색 계획 Bean은 `app.ai.follow-up-plan.*`을 읽지만 YAML의 환경변수 매핑은 `spring.ai.follow-up-plan.*` 아래에 있었다. `APP_AI_FOLLOW_UP_PLAN_*`만 변경해 Bean 설정이 바뀐다고 안내할 수 없다. [역할별 Client](../architecture/role-chat-clients.md)에 현재 경계를 기록했으며 Java·YAML은 최초 문서 작업에서 수정하지 않았다. 이후 [1차 개선·리팩토링](../plans/first-round-refactoring.md)에서 매핑을 수정했으며 최초 발견 기록은 유지한다.

## 검증의 범위

Markdown 84개 문서의 로컬 링크를 검사해 끊어진 경로 0건을 확인했다. `docs/` 전체 86개 파일의 UTF-8·제어문자 검사와 `git diff --check`도 통과했다. 외부 URL과 Markdown 내부 앵커는 이번 파일 경로 검사에 포함하지 않았다.

보관 문서의 당시 계약이나 과거 테스트 건수를 최신 실행 결과로 바꾸지 않았다. JUnit·실제 HTTP·DB migration·부하·유료 호출·원격 배포는 재실행하지 않았다. Git에서 제외된 `build/` 산출물은 존재·해시를 재검증하지 않았으며, 과거 보고서의 기록으로만 인용한다.
