# 문서 안내

현재 코드와 실행 방법은 [프로젝트 README](../README.md), 로컬 컨테이너와 모델 비교 환경은 [Docker 가이드](../docker/README.md)를 따른다. 아래 계획 문서는 구현 완료를 의미하지 않는다.

| 목적 | 문서 |
| --- | --- |
| 패키지 소유권·클래스 배치·구조 변경 | [도메인 구조 스킬](../.agents/skills/domain-structure/SKILL.md) |
| 전체 방향·구현 순서·미결정 사항 | [백엔드 재구축 계획](backend-rebuild-plan.md) |
| 구현된 법령 Tool·스키마·후속 작업 | [개발 인수인계](development-handoff.md) |
| 법령·판례 Tool 입력·반환·오류 계약 | [법령 API Tool](legal-api-tools.md) |
| 기존 채팅 API·법령 수집 회귀 기준 | [동작 기준선](backend-baseline.md) |
| 현재 인증 구현·프론트 CSRF·목표 정책 | [인증·보안 계획](plans/auth-security.md) |
| 개선 전 인증 위험과 설계 근거 | [JWT 보안 검토](jwt-security-review.md) |
| 수집·청킹·벡터 검색·근거·Advisor | [RAG·ETL 계획](plans/rag-etl.md) |
| 역할별 에이전트와 모델 평가 | [오케스트레이션·모델 계획](plans/orchestration-models.md) |
| 단계별 완료 조건·평가·복구 | [구현·검증 계획](plans/delivery-validation.md) |
| 미확정 보조 검색 후보 | [SerpApi 검토](plans/search-fallback.md) |
| 원문 스냅샷 수동 스키마 | [001-legal-source.sql](sql/001-legal-source.sql) |

문서에는 현재 구현, 목표, 과거 검증 결과를 구분해서 기록한다. 실행 방법은 루트·Docker README에서, 설계 결정은 해당 주제 문서에서 관리한다. 오래된 패키지 이미지와 중복 계획 보관본은 제거했으며 이전 내용은 Git 이력에서 확인할 수 있다.
