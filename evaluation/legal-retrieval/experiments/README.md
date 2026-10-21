# 실험 안내

같은 실험의 `data/`(고정 입력), `review/`(계획·검수), `reports/`(결과)를 가까이 모았다. [전체 요약](summary.md)과 아래 목적별 진입점을 사용한다.

| 목적 | 위치 | 상태 |
| --- | --- | --- |
| 4개 모델·청크·기존 질문 정제 비교 | [model-comparison](model-comparison/reports/chunk-experiments/overview.md) | 완료 |
| 법령 범위를 넓힌 검증 | [retrieval-validation/expanded-v1](retrieval-validation/expanded-v1/reports/kure-v1/README.md) | 완료 |
| 독립 작성 질문 검증 | [retrieval-validation/independent-v1](retrieval-validation/independent-v1/reports/kure-v1/interpretation.md) | 완료, 서비스 적용 근거 부족 |
| 단일 법률 쟁점 질의 변환 | [query-strategies/sentence-transform-v1](query-strategies/sentence-transform-v1/reports/kure-v1/interpretation.md) | 완료 |
| 변환 질의의 300·500·700자 비교 | [query-strategies/chunk-sizes-v1](query-strategies/chunk-sizes-v1/reports/kure-v1/interpretation.md) | 완료 |
| 상위 50개 후보의 정답 포함 범위 | [query-strategies/candidate-coverage-v1](query-strategies/candidate-coverage-v1/README.md) | KURE·BGE 완료, Qwen 실행 중 |
| 점수 임계값 탐색 | [query-strategies/threshold-sweep-v1](query-strategies/threshold-sweep-v1/reports/README.md) | 과거 분석 보존, 탐색 후보 초기화·현재 미사용 |
| Multi-Query·BM25·RRF 비교 | [hybrid-search/ablation-v1](hybrid-search/ablation-v1/review/README.md) | 기준선 완료, 후속 2~4단계 계획 |
| Qdrant 관리와 과거 검색 대조 | [infrastructure/qdrant](infrastructure/qdrant/README.md) | 데이터 보존, 목적별 별칭 적용 |

현재 후속 실험 기준은 KURE-v1·500자·중복 64자·법률 쟁점 변환이며 Hit@5와 Complete@5를 공동 우선으로 본다. 운영 확정은 아니다. 과거 모델 선정 기준은 당시 기록으로 유지한다.

## 경로와 재현

공통 실행 도구는 기존 `../scripts/`에, 큰 원문·벡터 캐시·로그는 Git에서 제외된 `../artifacts/`에 유지한다. 실행 시 모델을 다시 다운로드하거나 추론할 필요 없이 기존 벡터를 재사용할 수 있다.

[layout.json](../layout.json)은 과거 논리 실험 이름을 새 폴더에 연결한다. PowerShell 도구는 [Paths.ps1](../scripts/lib/Paths.ps1)의 `Get-DataPath`, `Get-ReviewPath`, `Get-ReportPath`를 사용한다. 예를 들어 `Get-DataPath 'final-validation-v1/manifest.json'`은 `retrieval-validation/expanded-v1/data/manifest.json`을 반환한다. 명시적인 데이터·보고서 디렉터리 인자도 유지한다.

고정 JSON·JSONL·CSV·PSV·바이너리는 바이트 그대로 이동했다. 매니페스트와 결과 JSON 안의 옛 경로 문자열은 해시로 연결된 출처 기록이므로 변경하지 않았다. 현재 위치를 찾을 때는 경로 해석기와 이 안내를 사용한다. 이동 대응표와 해시는 로컬 `artifacts/organization-v1/file-inventory.json`에 있다. 문서 링크와 실행 스크립트는 현재 경로로 갱신했다.
