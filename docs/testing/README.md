# 테스트와 성능 검증

현재까지의 시험 묶음은 [1차 테스트](first-round.md)다. 원본 결과와 [시각화](visual-results.md)는 리팩토링 전 기준선이며, 후속 작업은 [개선·리팩토링](../plans/first-round-refactoring.md)을 따른다.

소스·커밋 대조 기준일: 2026-10-30 (`b0b37d8`). 기능의 정상·실패 동작과 처리량·지연·자원 사용을 검증한 기록이다. 실측일·입력·장비·실패 기록은 각 결과 문서에 보존한다. 주요 수치는 [결과 시각화](visual-results.md)에서 비교한다.

## 확인한 범위

| 대상 | 완료한 검증 | 상세 기록 |
| --- | --- | --- |
| 상담 조회 | 합성 사용자 20명·200방·4,000메시지, 인증·복호화·권한 검사. 조회 512 VU·약 146 RPS 지속 검증 | [합성 데이터](test-data.md), [첫 실습](first-load-test.md), [조회 한계](read-capacity.md) |
| MySQL | 전용 200만 행으로 읽기·읽기/쓰기·동일 행 경합 측정 | [MySQL 직접 부하](mysql-capacity.md) |
| Redis·Refresh | Redis 명령/Lua·OOM·TTL 회복, 실제 Refresh 동시 회전·재사용·장애/응답 유실. 최종 100세션·약 883 RPS | [실행 결과](redis-performance.md), [계획](redis-performance-plan.md), [과거 중지 기록](redis-checkpoint.md) |
| 임베딩·벡터 검색 | 고정 법령 4종에서 1,032개 임베딩 생성·1,000개 적재/조회, 짧은 질문·512토큰 문서 동시성 측정 | [데이터셋](rag-dataset.md), [생성·적재](rag-generation.md), [임베딩 한계](embedding-capacity.md) |
| 리랭킹 | 실제 검색 후보 5/20개 단독 부하와 임베딩 동시 요청의 간섭·503·회복 검증 | [리랭커](reranker-capacity.md), [혼합 부하](mixed-rag-capacity.md) |
| 답변 스트림·최종 생성 | 대역 스트림/실패 14건, 실제 최종 생성 재실행 16호출 통과. 최초 인용 실패 보존 | [계획](answer-generation-plan.md), [결과](answer-generation-results.md) |
| 전체 RAG | 고정 20조문·29청크 색인과 호출 예산 게이트웨이 준비. 실제 모델 무저장 1상담 첫 답변 39.002초·완료 42.365초 | [격리 준비](full-rag-preparation.md), [첫 실제 상담](full-rag-first-live.md) |

## 관측과 실행 환경

| 문서 | 제공하는 내용 |
| --- | --- |
| [로컬 환경 조사](environment-readiness.md) | 초기 장비·개발 서비스 조사와 이후 준비 이력 |
| [전용 Docker 환경](../../docker/loadtest/README.md) | 격리 Compose·환경변수·시작 명령 |
| [MySQL·Redis 관측](database-monitoring.md) | DB 자체 지표와 API 지표의 차이 |
| [컨테이너 관측](container-monitoring.md) | CPU·메모리·네트워크·디스크 I/O |
| [Qdrant·RAG 관측](rag-monitoring.md) | 로컬 추론 큐·대기·벡터 DB와 시험 게이트웨이 지표 |

실행 도구는 `scripts/load-testing/`에 있다. 시험 순서·중단 조건은 [부하 테스트 계획](../plans/load-testing.md), 문서 조사·검증 방법은 [에이전트 작업 안내](../agent.md)를 따른다.

## 결과의 한계

- 같은 PC의 Docker 자원과 작은 고정 데이터를 사용한 결과다. VU는 휴식 중인 가상 사용자도 포함하며 운영 사용자 수나 전체 상담의 수용량을 보장하지 않는다.
- 전체 RAG 동시 부하·실제 저장형 D·후속 대화·장시간 안정성은 미실행이다. 최종 생성 재실행 성공도 최초 간헐적 인용 실패가 해결됐다는 증거는 아니다.
- Refresh의 503·응답 유실은 회전 미실행을 보장하지 않는다. 이전 토큰 재시도가 재사용 탐지와 세션 폐기로 이어지는 동작을 확인했다.
- 기본 JUnit/H2·대역·실제 모델·DB 직접 부하는 서로 다른 증거다. 유료 AI와 부하 시험은 기본 CI에 포함되지 않는다.
- 2026-10-30의 후속 스트림 순서·취소 대기·V8 검증 보완은 소스에 반영됐다. 과거 실측치를 해당 수정 후 재측정한 값으로 해석하지 않는다.
