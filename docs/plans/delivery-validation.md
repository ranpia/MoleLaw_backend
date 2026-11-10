# 검증·전달 계획

작업 순서는 [P01~P08](README.md), 최근 결과는 [검증 현황](../validation/validation-status.md)을 따른다. 이 문서는 검증 방법과 완료 보고 기준만 정의한다.

## 검증 층위

| 검증 | 명령·환경 | 확인 범위 |
| --- | --- | --- |
| 일반 회귀 | .\gradlew.bat test | H2·모킹, 권한·JSON 계약·실패·저장·OpenAPI |
| 빌드 | .\gradlew.bat bootJar | 실행 JAR 생성. 실행 중 JAR과 출력 경로 분리 가능 |
| MySQL | mysqlIntegrationTest | 독립 MySQL의 Flyway·제약·JPA·재기동 |
| Redis | redisIntegrationTest | 독립 Redis의 회전·TTL·재사용·경합·장애 |
| Qdrant | qdrantIntegrationTest | 독립 컬렉션·저장·검색 계약 |
| ETL 모의 HTTP | lawEtlSimulationTest | Postman 임베딩 대역·격리 Qdrant·실제 클라이언트 요청 |
| 최초 상담 모의 통합 | initialConsultationSimulationTest | 분류·수집·색인·답변·저장, 모델/법령 대역 |
| AI Search 모의 | aiLawSearchSimulationTest | 정상·빈·잘못된 응답·404, Qdrant 경로 독립성 |
| 실제 임베딩 | embeddingLiveTest | 실행 중 KURE와 격리 Qdrant. 법률 품질 평가와 별개 |
| 실제 판단 모델 | evidenceDecisionLiveTest | 합성 입력의 판단 계약·usage. 모델 접근·옵션 먼저 확인 |
| 추천 질문 모의 | initialQuestionSuggestionSimulationTest | 합성 HTTP 모델의 추천 질문·옵션·실패 계약 |
| 후속 상담 모의 | followUpConsultationSimulationTest | 요청별 동의·판례 도구·저장·근거 API |
| 스트림 모의 | answerStreamSimulationTest | 실제 HTTP 모델 대역·조기 delta·인용·저장·취소·슬롯 회복 |
| 실제 최종 생성 | answerGenerationLiveTest | 유료 opt-in, 고정 근거 writer 단독·시도/비용 제한. 전체 RAG와 별개 |
| 고정 RAG 자료 계약 | ragPreparationContractTest | 생성 자료의 실제 fingerprint·조문 청커 일치, 네트워크 미사용 |

선택 태스크는 Docker·대역 URL·실 API 키 등이 필요할 수 있으며 일반 test 통과로 실행 완료를 대신하지 않는다. 실제 AI Search 재시도는 보류 상태다. 외부 유료 호출·실 데이터 삭제·운영 배포를 검증 편의로 묶지 않는다.

CI는 dev push와 dev/main PR에서 `test bootJar` → `mysqlIntegrationTest` → `redisIntegrationTest`를 실행한다. 실패 시 `build/test-results/`와 `build/reports/tests/`를 7일 보존한다. 설정 근거는 [test.yml](../../.github/workflows/test.yml)이며, 워크플로 정의만으로 특정 실행의 성공을 의미하지 않는다.

## 품질 평가

독립 질문셋에 법령명·조문 지정, 일상 표현, 공항 건축 같은 복합 쟁점, 시행일, 무관 자료, 부족 사실, 후속 사실 정정과 주제 변경을 포함한다. 정답은 모델 출력과 독립 검토하며 프롬프트 예시와 평가셋을 분리한다.

고정 색인 검색과 동적 수집은 별도 보고한다. 정답 법령/조문 포함률·오수집·부족 근거 답변·인용 충실성·종료 사유·실 토큰/비용·p95를 기록한다. 합격선은 D02에서 결정한다.

## 전달 게이트

변경 기능의 정상·실패·권한 경로를 확인하고 필요한 실제 저장소·모델 검증을 추가한다. 날짜·명령·환경·모델/프롬프트/색인 버전·테스트 실패/건너뜀·미검증 항목을 적는다.

새 모델 설정만으로 품질 검증을 완료했다고 적지 않는다. 실제 요청을 실행하지 않은 문서 수정은 링크·경로·코드 대조 결과만 보고한다. main 통합·push·수동 배포는 별도 요청 범위를 따른다. 운영 비밀값·TLS·복구·브라우저 검증이 끝나기 전 로컬 성공을 배포 준비 완료로 표시하지 않는다.
