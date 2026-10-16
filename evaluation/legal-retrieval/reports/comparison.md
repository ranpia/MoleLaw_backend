# 법령 임베딩 모델·청크 비교

2026-10-16 기준 3개 모델 × 300 / 500 / 700문자, 총 9개 구성 완료. 중복은 64문자이며 동일 원문 5,486개 단위·질문 110개·정답·분할을 사용했다. 차원 축소는 아직 평가하지 않았다.

## 기존 검색 지표

| Model | Dimensions | Characters | Dev Recall@5 | Dev MRR | Held-out top-1 | Held-out Recall@5 | Held-out MRR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| bge-m3:latest | 1024 | 300 | 57.39% | 0.5196 | 52.86% | 72.79% | 0.6762 |
| bge-m3:latest | 1024 | 500 | 64.06% | 0.5928 | 60.0% | 75.40% | 0.7226 |
| bge-m3:latest | 1024 | 700 | 65.72% | 0.5909 | 58.57% | 73.62% | 0.7179 |
| embeddinggemma:latest | 768 | 300 | 61.28% | 0.5815 | 65.71% | 74.69% | 0.7417 |
| embeddinggemma:latest | 768 | 500 | 62.28% | 0.5854 | 70.0% | 79.93% | 0.7861 |
| embeddinggemma:latest | 768 | 700 | 64.22% | 0.6065 | 62.86% | 78.14% | 0.7422 |
| kure-v1:official | 1024 | 300 | 57.11% | 0.6160 | 51.43% | 71.71% | 0.6592 |
| kure-v1:official | 1024 | 500 | 57.28% | 0.6245 | 55.71% | 73.81% | 0.6935 |
| kure-v1:official | 1024 | 700 | 58.39% | 0.6153 | 48.57% | 76.48% | 0.6526 |

## 추가 지표: development

양성 30개 질문의 macro 평균이다. Hit@5는 정답 하나 이상, Complete@5는 정답 전체 회수, Precision@5는 검색 5개 중 정답 비율이다. nDCG는 정답의 순서를 반영한다.

| 모델 | 최대 문자 | Hit@5 | Complete@5 | Precision@5 | nDCG@5 | nDCG@10 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| bge-m3:latest | 300 | 70% | 43.33% | 18.67% | 0.4725 | 0.5238 |
| bge-m3:latest | 500 | 76.67% | 50% | 20% | 0.5377 | 0.5863 |
| bge-m3:latest | 700 | 80% | 50% | 21.33% | 0.5469 | 0.5791 |
| embeddinggemma:latest | 300 | 73.33% | 46.67% | 20% | 0.5301 | 0.5675 |
| embeddinggemma:latest | 500 | 73.33% | 50% | 20% | 0.5357 | 0.5719 |
| embeddinggemma:latest | 700 | 76.67% | 50% | 21.33% | 0.5543 | 0.5821 |
| kure-v1:official | 300 | 66.67% | 43.33% | 18.67% | 0.5316 | 0.5856 |
| kure-v1:official | 500 | 73.33% | 43.33% | 18.67% | 0.537 | 0.5987 |
| kure-v1:official | 700 | 73.33% | 43.33% | 19.33% | 0.5438 | 0.6021 |

## 결과 해석

- 미리 정한 development Recall@5 기준 우선 후보는 BGE-M3 700문자(65.72%)다. Gemma 700문자(64.22%)와 차이는 1.50%p이며, 작은 개발 표본만으로 일반적인 우열을 확정하지 않는다.
- 첫 정답 순위의 MRR은 KURE 500문자(0.6245), nDCG@5는 Gemma 700문자(0.5543), nDCG@10은 KURE 700문자(0.6021)가 가장 높다. 여러 근거의 회수와 앞쪽 배치에서 지표별 장점이 다르다.
- Complete@5의 개발 최고값은 50%다. 정답 하나를 찾는 Hit@5가 높아도 필요한 모든 조문을 확보한 것은 아니다.
- 추가 지표는 결과 확인 후 도입한 보조 분석이다. 기존 Recall@5 선택 규칙을 유지하고 공개된 held-out으로 반복 튜닝하지 않는다.

## 유사도 점수 읽기

700문자 조건의 휴게시간 질문에서 BGE-M3는 근로기준법 제50조(근로시간)를 0.5869로, KURE는 정답 제54조(휴게)를 0.5602로 검색했다. 유사도는 정답 확률이 아니며 모델별 원시 점수로 우열을 비교하지 않는다. 조문 점수는 자식 청크의 최대 코사인 유사도다.

예시 전체와 검색 조문명: [유사도 CSV](similarity-examples.csv). 질문별 추가 지표: [CSV](ranking-queries.csv).

## 평가 조건과 한계

Similarity examples and metric definitions: [guide](similarity-and-metrics.md). Additional Hit@5, Complete@5, Precision@5 and nDCG@5/10: [ranking comparison](ranking-comparison.md).

Use development results to choose chunk/model settings. Held-out rows are descriptive final checks, not a repeated tuning objective.

Primary selection metric: development Recall@5. Supporting metrics: MRR, Recall@10, in-corpus acceptance and out-of-corpus false acceptance; see CSV.

Recall ranks distinct source articles/annexes by maximum child-chunk score. No-answer rates use only five held-out negative questions and score-based thresholds, not generated answers.

Raw similarity thresholds are fitted independently per configuration; do not compare absolute cosine scores across models.

Query latency is one warm request per question, not a repeated load test. Character limits are not tokenizer-exact token limits.

CSV vector size is chunk count x dimension x 4 bytes for float32 values only; excludes IDs, metadata, cache overhead and any ANN index. Cached indexing times are not model throughput measurements.

KURE uses official Sentence Transformers/PyTorch float32; the existing Gemma/BGE runs use Ollama BF16/F16. Runtime and precision differ, so latency is not a pure model comparison. Legacy report JSON remains unchanged; unrecorded dtype is explicitly marked in CSV.
