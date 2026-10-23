# 문서 안내

기준일: **2026-10-23**, 법령·후속 상담 PR의 병합 내용을 통합한 `dev` 기준이다. `main` 반영·배포 완료와는 다르다.

## 먼저 읽을 문서

| 목적 | 문서 |
| --- | --- |
| 프로젝트 소개·로컬 실행 | [프로젝트 README](../README.md), [Docker](../docker/README.md) |
| 현재 구현과 제한 | [개발 현황](development-status.md) |
| 사용자가 정할 항목 | [사용자 판단 대기](decisions/README.md) |
| 이미 확정한 방향 | [확정 결정](decisions/confirmed.md) |
| 앞으로 할 일과 순서 | [작업 계획](plans/README.md) |
| 검증 완료·미검증 구분 | [검증 현황](validation-status.md) |
| 담당자 인수인계 | [인수인계](development-handoff.md) |
| 브랜치·추적 파일·실험 워크트리 | [브랜치 작업 가이드](branch-workflow.md) |

## 현재 동작·운영 계약

| 영역 | 문서 |
| --- | --- |
| 최초 질문부터 답변·저장까지 | [최초 상담](initial-consultation.md) |
| 분류·검색·전체 페이지 리랭크 | [질문 라우팅](question-routing.md) |
| 후보 목록·본문 읽기·분리 입력 예산 | [근거 본문 읽기](evidence-reading.md) | [판단 계약](evidence-decision.md) |
| 모델·추론 수준·Builder·Advisor | [역할별 Client](role-chat-clients.md) |
| 법령·판례 검색·상세 Tool | [API Tool](legal-api-tools.md) |
| 벡터화·상위 15개 조문 검색 | [벡터 Tool](law-vector-tools.md) |
| ETL·저장 검증·수동 재색인 | [ETL 로컬 가이드](law-etl-local.md) |
| 보류된 조문 API·비교 경로 | [AI Search 비교](ai-search-comparison.md) |
| 최초 근거·후속 인용 | [상담방 근거](room-evidence.md) |
| 동의 기반 후속 판례·근거 목록/본문 API | [후속 상담](follow-up-consultation.md) |
| 암호화·저장·조회 | [상담 저장](chat-storage.md) |
| Access·Refresh | [Access](stateless-access.md), [Refresh](refresh-rotation.md) |
| 사용자 검증·계정 모델 | [사용자 계정](user-account-validation.md) |
| 현재 테이블·마이그레이션 | [스키마 현황](schema-rebuild-status.md), [MySQL 실행](mysql-setup.md) |
| 패키지·DTO·API·폴더 | [패키지와 API](package-api-organization.md), [폴더](folder-structure.md) |

## 계획과 과거 자료

주제별 미완료 작업은 [계획 목록](plans/README.md), 과거 정책·기준선은 [보관 안내](archive/README.md)를 따른다. [재구축 요약](backend-rebuild-plan.md)은 방향을 설명하는 짧은 진입점이다.

현재 문서는 실제 동작만 설명하고, 미완료 작업은 계획에, 정책 선택은 사용자 판단 문서에 둔다. 과거 테스트 수치와 실험 결과를 최신 검증으로 승격하지 않는다. 구현 변경 시 해당 계약·개발 현황·관련 계획을 함께 갱신하며 확정한 결정을 다시 미결정으로 올리지 않는다.
