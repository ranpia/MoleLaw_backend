# 개발 인수인계

기준일: 2026-10-23. 작업 브랜치는 dev이며 미커밋 구현·문서 변경이 있다. main 통합·push·배포 완료를 의미하지 않는다.

## 인수 순서

1. [개발 현황](development-status.md)에서 구현·제한을 확인한다.
2. [확정 결정](decisions/confirmed.md)과 [사용자 판단](decisions/README.md)을 구분한다.
3. [작업 계획](plans/README.md)의 P01부터 순서와 의존성을 확인한다.
4. 실제 실행 전 [최초 상담](initial-consultation.md), [Docker](../docker/README.md), [환경 예제](../.env.example)를 확인한다.

## 코드 진입점

| 영역 | 주요 클래스 |
| --- | --- |
| 상담 조정·저장 | ChatService, LegalAnswerService, ChatTurnService |
| 질문·수집·판단 | QuestionRetrievalPlanner, LegalEvidenceCollector, EvidenceDecisionPlanner |
| 법령 후보 리랭크 | LawCandidateSelector, LawCandidateSelection |
| 원문·ETL | LegalApiToolService, LawSourceDocumentReader, LawArticleDocumentTransformer, LawQdrantDocumentWriter |
| 벡터화·검색 | LawVectorizationToolService, LawRagSearchToolService, LawVectorClient |
| 모델·메모리 | AiConfig, EvidenceChatClientConfig, AnswerChatClientConfig, PersistedChatMemory |
| 별도 조문 검색 | OpenLawAiSearchClient, LawAiSearchToolService, AiSearchEvidenceCollector |

## 바로 주의할 점

- 새 모델은 역할별로 지정했으나 실 API 접근·응답 품질은 미검증이다. 재시작 전 이미 실행 중인 서버에 새 설정이 적용됐다고 가정하지 않는다.
- 일반 법령 검색은 전체 페이지 검토 후 선별한다. 예산이 먼저 끝나면 해당 조건의 부분 결과를 색인하지 않는다. AI Search·로컬 수동 ETL 계약은 별도다.
- Qdrant 적재 후 검증을 통과해야 COMPLETE다. 실패 세대·구세대 물리 정리와 영속 작업 복구는 미구현이다.
- 서비스용 컬렉션 정리는 완료했지만 실험용 evaluation 자료·컬렉션은 보존한다. 정리 기록을 매번 실행하는 초기화 절차로 사용하지 않는다.
- Flyway 적용 버전은 V1~V4이며 적용된 SQL을 수정하지 않는다. 사용자·원문·상담 DB를 재초기화하지 않는다.
- 후속 상담에 새 수집을 연결하거나 모델에 실행 권한·예산·인용 ID 생성을 맡기지 않는다.

## 검증과 전달

일반 회귀는 `.\gradlew.bat test`, JAR은 `.\gradlew.bat bootJar`다. 실행 중인 JAR과 경로가 겹치면 별도 출력 경로를 사용한다. 실제 저장소·임베딩·유료 모델은 별도 선택 검증이다. 최신 결과는 [검증 현황](validation-status.md)을 따른다.

개발은 dev에서 계속한다. main 반영과 EC2 수동 배포는 테스트·설정·복구 준비 후 별도 요청 범위에서 진행한다.
