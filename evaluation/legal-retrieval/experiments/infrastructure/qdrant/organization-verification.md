# 2026-10-20 정리 검증

실험 브랜치 `experiment/legal-embedding-evaluation`에서 파일 296개를 목적별로 이동했다. 이 중 Markdown을 제외한 고정 입력·결과·바이너리 263개는 이동 전후 SHA-256이 모두 같다. 문서·실행 경로·Git 줄바꿈/제외 규칙을 갱신했다. 큰 원문·벡터 캐시·로그는 기존 `artifacts/`에 유지했다.

실측 Qdrant 물리 컬렉션 29개, 포인트 합계 113,982개를 보존하고 별칭 29개를 추가했다. 모델 비교 17개/85,536포인트, 검색 검증 4개/11,740포인트, 질의 전략 8개/16,706포인트다. 별칭 전후 정확한 포인트 수·벡터 스키마·소유권 메타데이터가 같고, 별칭과 물리 이름으로 조회한 샘플 포인트의 벡터·페이로드가 일치했다. 같은 적용을 다시 실행해 추가 변경 0건을 확인했다. 벡터 삭제·복제·모델 추론·평가 재실행은 하지 않았다. 작업 시 C 드라이브 여유 공간은 약 75.3GB였다.

다음 기존 검증을 실제 실행해 통과했다.

- `test-evaluation-contract.ps1`: 청크별 데이터·보고서 해시와 평가 계약, 실패 입력 거부
- `test-final-validation.ps1`: 확장 30법령/270질문/10,999청크의 고정 입력과 정답 검증
- `run-kure-independent-validation.ps1 -ValidateOnly`: 독립 질문·원문·모델·정답·기준값 검증
- `run-kure-legal-query-transform.ps1 -ValidateOnly`: 100개 변환과 검수·고정 기준선 검증
- `run-kure-legal-query-chunks.ps1 -ValidateOnly`: 500·700자 코퍼스의 6,527개 원문 재현과 공유 입력 검증
- `test-qdrant-lab.ps1`: 세 청크 경로·컬렉션 분리·소유권·차원·모델 계약 검증
- `test-ranking-metrics.ps1`, `test-evaluator.ps1`, `test-query-rewrites.ps1`: 순위·코사인·캐시·질의 계약 검증
- `manage-qdrant-lab.ps1 -Action Verify`: 실제 Qdrant 별칭 29개와 데이터 정합성 검증
- 전체 평가 PowerShell 구문 분석, 이동된 문서를 포함한 로컬 Markdown 링크 검사 통과

실험 질문·정답·검색 순위·성능 지표는 변경하지 않았다. 실제 서비스 답변 품질, 새 모델, Multi-Query·BM25·RRF 후속 단계는 이번 정리 검증의 범위가 아니다. 고정 매니페스트의 과거 경로 문자열은 출처 기록으로 보존한다. 현재 경로 연결 방식은 [실험 안내](../../README.md)의 경로 설명을 따른다.
