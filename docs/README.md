# 문서 안내

기준일: **2026-10-27**, 현재 dev 작업 코드 기준. 기능 구현과 실제 법률 품질·운영 검증은 구분한다.

## 먼저 읽기

1. [구현 기능과 질문 처리 다이어그램](project/features-and-question-flow.md)
   - [로그인](auth/login-flow.md) · [채팅방 생성·제목 변경](chat/chat-room-management.md)
2. [현재 개발 현황](project/development-status.md) · [검증 현황](validation/validation-status.md)
3. [다음 작업](plans/README.md) · [사용자 판단 대기](decisions/README.md) · [확정 결정](decisions/confirmed.md)
4. [프로젝트 소개·실행](../README.md) · [Docker 실행](../docker/README.md)

## 기능별 문서

| 분류 | 내용 | 주요 문서 |
| --- | --- | --- |
| [프로젝트](project/README.md) | 기능 현황·인수인계·브랜치 | [기능·다이어그램](project/features-and-question-flow.md), [인수인계](project/development-handoff.md) |
| [인증·사용자](auth/README.md) | JWT·Refresh·계정 | [Access](auth/stateless-access.md), [Refresh](auth/refresh-rotation.md), [사용자 검증](auth/user-account-validation.md) |
| [상담·대화](chat/README.md) | 질문 처리·기록·스트림·근거 | [최초 상담](chat/initial-consultation.md), [후속 상담](chat/follow-up-consultation.md), [스트리밍](chat/initial-answer-stream.md), [근거](chat/room-evidence.md) |
| [법령·판례·RAG](law/README.md) | 질문 분석·수집·검색·ETL | [라우팅](law/question-routing.md), [ETL](law/law-etl-local.md), [조문 리랭크](law/article-reranking.md), [판례 검색](law/case-retrieval-reranking.md) |
| [데이터베이스](database/README.md) | MySQL·스키마 | [실행](database/mysql-setup.md), [스키마](database/schema-rebuild-status.md) |
| [코드·모델 구조](architecture/README.md) | 패키지·API·역할별 Client | [패키지·API](architecture/package-api-organization.md), [모델](architecture/role-chat-clients.md) |
| [검증](validation/README.md) | 검증 기록·제한 | [검증 현황](validation/validation-status.md) |
| [작업 계획](plans/README.md) | 미완료 작업·순서 | [모델·오케스트레이션](plans/orchestration-models.md), [RAG·ETL](plans/rag-etl.md) |
| [사용자 결정](decisions/README.md) | 판단 대기·확정 방향 | [확정 결정](decisions/confirmed.md) |
| [과거 기록](archive/README.md) | 폐기·변경된 기준과 SQL | 현재 동작의 근거로 사용하지 않음 |

## 문서 배치 원칙

- 현재 기능은 해당 기능 폴더에 두고, 추가 작업은 plans/, 정책 선택은 decisions/, 과거 기록은 archive/에 둔다.
- 각 폴더의 README에서 상세 문서를 선택한다. 새 문서를 추가하면 해당 목차도 갱신한다.
- 문서를 이동할 때 상대 링크·코드·스크립트의 경로를 함께 바꾼다.
- 코드 예시는 [examples/initial-answer-stream.js](examples/initial-answer-stream.js), 실행 환경은 [Docker](../docker/README.md)를 따른다.
- 문서 정리는 외부 API·법률 품질 재검증이나 원격 배포 완료를 의미하지 않는다.
