# 문서 안내

- [Access stateless 구현](stateless-access.md): 버전 3·5분 수명·Redis 장애와 폐기 계약

현재 코드와 실행 방법은 [프로젝트 README](../README.md), 로컬 컨테이너와 모델 비교 환경은 [Docker 가이드](../docker/README.md)를 따른다. 아래 계획 문서는 구현 완료를 의미하지 않는다.

| 목적 | 문서 |
| --- | --- |
| 패키지 소유권·클래스 배치·구조 변경 | [도메인 구조 스킬](../.agents/skills/domain-structure/SKILL.md) |
| 전체 방향·구현 순서·미결정 사항 | [백엔드 재구축 계획](backend-rebuild-plan.md) |
| 구현된 법령 Tool·스키마·후속 작업 | [개발 인수인계](development-handoff.md) |
| 법령·판례 Tool 입력·반환·오류 계약 | [법령 API Tool](legal-api-tools.md) |
| 기존 채팅 API·법령 수집 회귀 기준 | [동작 기준선](backend-baseline.md) |
| 현재 인증 구현·CSRF·Access 5분 stateless/Redis Refresh 목표 | [인증·보안 계획](plans/auth-security.md) |
| 구현된 Refresh 회전·쿠키/JSON·재사용·폐기·Redis 통합 검증 | [Refresh 회전 구현](refresh-rotation.md) |
| MySQL 마이그레이션·업무/ETL 스키마 | [MySQL 계획](plans/mysql-schema.md) |
| 빈 MySQL 초기화·통합 테스트 | [MySQL 실행 가이드](mysql-setup.md) |
| 개선 전 인증 위험과 설계 근거 | [JWT 보안 검토](jwt-security-review.md) |
| 수집·청킹·벡터 검색·근거·Advisor | [RAG·ETL 계획](plans/rag-etl.md) |
| 주 법령 20개·보충 근거·질문 110개의 임베딩 검색 평가 | [검색 평가 실행 안내](../evaluation/legal-retrieval/README.md) |
| 3개 모델·3개 청크의 평가지표와 결과 해석 | [모델·청크 비교](../evaluation/legal-retrieval/reports/comparison.md) |
| 모델별 유사도 예시·지표 정의·평가 한계 | [유사도와 평가지표](../evaluation/legal-retrieval/reports/similarity-and-metrics.md) |
| Gemma·BGE-M3·KURE-v1 700문자에서 LLM 질문 정제의 효과 | [질문 정제 전후 비교](../evaluation/legal-retrieval/reports/query-rewrite-v1/comparison.md) |
| Qdrant 실험 컬렉션 적재·조회·검색·재색인·삭제 | [Qdrant 실험 환경](../evaluation/legal-retrieval/reports/qdrant-lab/README.md) |
| 전체 질문의 Qdrant 전수·근사 검색 품질과 반복 지연시간 | [Qdrant 벤치마크](../evaluation/legal-retrieval/reports/qdrant-lab/benchmark/comparison.md) |
| 임베딩 실험 캐시·정답 검증·보안 검토 | [2026-10-15 실험 검토](embedding-experiment-review-2026-10-15.md) |
| 역할별 에이전트와 모델 평가 | [오케스트레이션·모델 계획](plans/orchestration-models.md) |
| 단계별 완료 조건·평가·복구 | [구현·검증 계획](plans/delivery-validation.md) |
| 미확정 보조 검색 후보 | [SerpApi 검토](plans/search-fallback.md) |
| 자동 초기 스키마 | [Flyway V1](../src/main/resources/db/migration/V1__initial_schema.sql) |

문서에는 현재 구현, 목표, 과거 검증 결과를 구분해서 기록한다. 실행 방법은 루트·Docker README에서, 설계 결정은 해당 주제 문서에서 관리한다. 오래된 패키지 이미지와 중복 계획 보관본은 제거했으며 이전 내용은 Git 이력에서 확인할 수 있다.
