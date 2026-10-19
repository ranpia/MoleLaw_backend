# 질문 정제 실험

700문자·중복 64문자 조건에서 원본 질문과 LLM 정제 질문의 검색 성능을 비교한다. 모델은 EmbeddingGemma 768차원, BGE-M3·KURE-v1 1024차원이다. 정제본은 대화형 Codex 어시스턴트가 작성했으며 GPT-4 API로 생성한 결과가 아니다. 대화에 기존 결과가 공개되어 있어 독립 블라인드 검증으로 해석하지 않는다.

정제 원칙은 일상 표현을 법률 검색에 적합하게 바꾸되 사실관계·주체·부정·시점·수치·요구사항을 유지하는 것이다. 법률명과 조문 번호를 새로 추측하여 넣지 않는다. 원본 질문만으로 작성한 정제본을 결과 실행 전에 고정하고, 세 임베딩 모델에 같은 정제본을 사용한다. 질문 의도가 유지되는지 사람이 재검토할 수 있도록 원문도 함께 저장했다. 의미 보존이 자동으로 증명된 것은 아니다.

- [고정 질문 110개](../../review/query-rewrites-v1.json): 원문·정제본·작성 방식·입력 해시.
- [정제 지침](../../review/query-rewrite-prompt-v1.txt).
- 모델별 `report.json`, `summary.csv`, `queries.csv`, `top10.csv`: 원본 질문과 실제 검색 질문, 순위·유사도·정답 여부.
- [전후 성능 비교와 해석](comparison.md), [성능 CSV](comparison.csv), [질문별 전후 결과](paired-queries.csv)에 전체 결과를 집계했다.

문서 벡터는 기존 캐시만 사용한다. 누락된 문서 벡터가 있으면 실행을 중단한다. 정제본의 해시와 원본 데이터·모델 digest·입력 형식·차원·스레드·배치 크기가 기준선과 일치하는지 검사한다. 원본 질문 결과와 데이터는 덮어쓰지 않는다. Qdrant에는 쓰지 않는다.

검증용 질문에서 모델이나 정제 지침을 반복 조정하지 않는다. 정답 없는 질문은 분할마다 5개뿐이며, 검색 점수 기반 오수용률은 실제 생성 답변의 거절률이 아니다. 원본 임계값을 고정한 결과와 개발용 정제 질문에서 임계값을 다시 정한 결과를 함께 보고한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-query-rewrites.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-query-rewrite.ps1 -Model embeddinggemma
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-query-rewrite.ps1 -Model bge-m3
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-query-rewrite.ps1 -Model kure-v1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-query-rewrite.ps1
```

2026-10-16: Gemma·BGE-M3 각각 정제 질문 110개 평가 완료. 검증용 Recall@5는 Gemma 78.14% → 78.43%, BGE-M3 73.62% → 80.95%다. 검증용 Complete@5는 Gemma 65.71% → 62.86%, BGE-M3 60.00% → 68.57%다. 이번 정제본의 효과는 BGE-M3에서 더 컸지만 모든 질문이 개선된 것은 아니다.

사용자 변경 지시로 중단한 OpenAI 생성 스크립트와 중간 응답은 제거했으며, 해당 응답은 평가에 사용하지 않았다. 실제 자동 정제의 API 지연·비용은 이번 실험에서 측정하지 않는다.

2026-10-19: KURE-v1도 같은 정제본 110개로 평가했다. 개발용 Recall@5는 58.39% → 73.50%, 검증용은 76.48% → 81.12%다. 검증용 정제 Recall@5의 BGE-M3 80.95%와 차이는 약 0.17%p다. KURE의 검증용 MRR은 0.6526 → 0.7699로 올랐지만 BGE-M3 정제본의 0.8036보다 낮다. 모델별 장점이 달라 Recall 한 항목으로 확정하지 않는다. KURE는 기존 revision·Sentence Transformers 버전·float32를 유지했다.
