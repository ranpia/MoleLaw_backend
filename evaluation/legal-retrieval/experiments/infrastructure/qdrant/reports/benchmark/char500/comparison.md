# Qdrant 전체 질문 검색 비교

500 문자 청크에서 모델별 원본 질문 110개와 고정 정제 질문 110개를 실행했다. 같은 질문 벡터로 local(기존 C# 전수 계산), exact(Qdrant 전수), ann(Qdrant 근사 설정)을 비교했다. 컬렉션과 문서 벡터는 수정하지 않았다.

original은 원본 질문, rewritten은 정제 질문이다. development는 개발용 양성 30개, held_out은 검증용 양성 70개다. 정답 없는 질문은 품질 지표에서 제외하고 시간·검색 일치율에는 포함했다.

## 검색 품질

| 모델 | 질문 | 분할 | 방식 | Recall@5 | Recall@10 | 1위 적중률 | Complete@5 | MRR@10 | nDCG@5 | nDCG@10 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| embeddinggemma | original | development | local | 62.28% | 71% | 43.33% | 50% | 0.5759 | 0.5357 | 0.5719 |
| embeddinggemma | original | development | exact | 62.28% | 71% | 43.33% | 50% | 0.5759 | 0.5357 | 0.5719 |
| embeddinggemma | original | development | ann | 62.28% | 71% | 43.33% | 50% | 0.5759 | 0.5357 | 0.5719 |
| embeddinggemma | original | held_out | local | 79.93% | 84.6% | 70% | 67.14% | 0.7831 | 0.7385 | 0.7589 |
| embeddinggemma | original | held_out | exact | 79.93% | 84.6% | 70% | 67.14% | 0.7831 | 0.7385 | 0.7589 |
| embeddinggemma | original | held_out | ann | 79.93% | 84.6% | 70% | 67.14% | 0.7831 | 0.7385 | 0.7591 |
| embeddinggemma | rewritten | development | local | 72% | 84.06% | 53.33% | 53.33% | 0.6847 | 0.6301 | 0.6819 |
| embeddinggemma | rewritten | development | exact | 72% | 84.06% | 53.33% | 53.33% | 0.6847 | 0.6301 | 0.6819 |
| embeddinggemma | rewritten | development | ann | 72% | 84.06% | 53.33% | 53.33% | 0.6847 | 0.6301 | 0.6819 |
| embeddinggemma | rewritten | held_out | local | 80.81% | 87.1% | 71.43% | 67.14% | 0.8116 | 0.762 | 0.7884 |
| embeddinggemma | rewritten | held_out | exact | 80.81% | 87.1% | 71.43% | 67.14% | 0.8116 | 0.762 | 0.7884 |
| embeddinggemma | rewritten | held_out | ann | 80.81% | 87.1% | 71.43% | 67.14% | 0.8116 | 0.762 | 0.7884 |
| bge-m3 | original | development | local | 64.06% | 76.67% | 50% | 50% | 0.5912 | 0.5377 | 0.5863 |
| bge-m3 | original | development | exact | 64.06% | 76.67% | 50% | 50% | 0.5912 | 0.5377 | 0.5863 |
| bge-m3 | original | development | ann | 64.06% | 76.67% | 50% | 50% | 0.5912 | 0.5377 | 0.5863 |
| bge-m3 | original | held_out | local | 75.4% | 84.33% | 60% | 61.43% | 0.7198 | 0.6759 | 0.7105 |
| bge-m3 | original | held_out | exact | 75.4% | 84.33% | 60% | 61.43% | 0.7198 | 0.6759 | 0.7105 |
| bge-m3 | original | held_out | ann | 75.4% | 84.33% | 60% | 61.43% | 0.7198 | 0.6759 | 0.7105 |
| bge-m3 | rewritten | development | local | 72.67% | 78.61% | 60% | 53.33% | 0.6911 | 0.6364 | 0.6618 |
| bge-m3 | rewritten | development | exact | 72.67% | 78.61% | 60% | 53.33% | 0.6911 | 0.6364 | 0.6618 |
| bge-m3 | rewritten | development | ann | 72.67% | 78.61% | 60% | 53.33% | 0.6911 | 0.6364 | 0.6618 |
| bge-m3 | rewritten | held_out | local | 80.48% | 87.52% | 68.57% | 67.14% | 0.7931 | 0.7434 | 0.772 |
| bge-m3 | rewritten | held_out | exact | 80.48% | 87.52% | 68.57% | 67.14% | 0.7931 | 0.7434 | 0.772 |
| bge-m3 | rewritten | held_out | ann | 80.48% | 87.52% | 68.57% | 67.14% | 0.7931 | 0.7434 | 0.772 |
| kure-v1 | original | development | local | 57.28% | 73.33% | 53.33% | 43.33% | 0.6194 | 0.537 | 0.5987 |
| kure-v1 | original | development | exact | 57.28% | 73.33% | 53.33% | 43.33% | 0.6194 | 0.537 | 0.5987 |
| kure-v1 | original | development | ann | 57.28% | 73.33% | 53.33% | 43.33% | 0.6194 | 0.537 | 0.5987 |
| kure-v1 | original | held_out | local | 73.81% | 82.79% | 55.71% | 61.43% | 0.691 | 0.6476 | 0.6846 |
| kure-v1 | original | held_out | exact | 73.81% | 82.79% | 55.71% | 61.43% | 0.691 | 0.6476 | 0.6846 |
| kure-v1 | original | held_out | ann | 73.81% | 82.79% | 55.71% | 61.43% | 0.691 | 0.6476 | 0.6846 |
| kure-v1 | rewritten | development | local | 73.5% | 80.83% | 60% | 56.67% | 0.7011 | 0.6549 | 0.6822 |
| kure-v1 | rewritten | development | exact | 73.5% | 80.83% | 60% | 56.67% | 0.7011 | 0.6549 | 0.6822 |
| kure-v1 | rewritten | development | ann | 73.5% | 80.83% | 60% | 56.67% | 0.7011 | 0.6549 | 0.6822 |
| kure-v1 | rewritten | held_out | local | 81.12% | 83.6% | 64.29% | 67.14% | 0.7625 | 0.7319 | 0.7424 |
| kure-v1 | rewritten | held_out | exact | 81.12% | 83.6% | 64.29% | 67.14% | 0.7625 | 0.7319 | 0.7424 |
| kure-v1 | rewritten | held_out | ann | 81.12% | 83.6% | 64.29% | 67.14% | 0.7625 | 0.7319 | 0.7424 |
| qwen3-0.6b | original | development | local | 66.17% | 72.67% | 46.67% | 50% | 0.6018 | 0.5648 | 0.5898 |
| qwen3-0.6b | original | development | exact | 66.17% | 72.67% | 46.67% | 50% | 0.6018 | 0.5648 | 0.5898 |
| qwen3-0.6b | original | development | ann | 66.17% | 72.67% | 46.67% | 50% | 0.6018 | 0.5648 | 0.5898 |
| qwen3-0.6b | original | held_out | local | 78.4% | 84.86% | 58.57% | 67.14% | 0.7237 | 0.6915 | 0.7185 |
| qwen3-0.6b | original | held_out | exact | 78.4% | 84.86% | 58.57% | 67.14% | 0.7237 | 0.6915 | 0.7185 |
| qwen3-0.6b | original | held_out | ann | 78.4% | 84.86% | 58.57% | 67.14% | 0.7237 | 0.6915 | 0.7185 |
| qwen3-0.6b | rewritten | development | local | 67.67% | 80.17% | 56.67% | 53.33% | 0.6793 | 0.6037 | 0.6505 |
| qwen3-0.6b | rewritten | development | exact | 67.67% | 80.17% | 56.67% | 53.33% | 0.6793 | 0.6037 | 0.6505 |
| qwen3-0.6b | rewritten | development | ann | 67.67% | 80.17% | 56.67% | 53.33% | 0.6793 | 0.6037 | 0.6505 |
| qwen3-0.6b | rewritten | held_out | local | 82.17% | 87.48% | 67.14% | 71.43% | 0.7845 | 0.7415 | 0.7668 |
| qwen3-0.6b | rewritten | held_out | exact | 82.17% | 87.48% | 67.14% | 71.43% | 0.7845 | 0.7415 | 0.7668 |
| qwen3-0.6b | rewritten | held_out | ann | 82.17% | 87.48% | 67.14% | 71.43% | 0.7845 | 0.7415 | 0.7668 |

MRR@10은 첫 정답이 10위 밖이면 0으로 처리한다. 이전 보고서의 전체 순위 MRR과 정의가 다르므로 직접 같은 수치로 비교하지 않는다. Hit@5·Precision@5도 [CSV](comparison.csv)에 보존했다.

## 검색 결과가 얼마나 같은가?

| 모델 | 질문 | ANN·전수 상위 10개 겹침 | 기존 계산·Qdrant 전수 겹침 | 과거 Recall@5가 달라진 질문 수 |
| --- | --- | ---: | ---: | ---: |
| embeddinggemma | original | 99.64% | 100% | 0 |
| embeddinggemma | rewritten | 99.73% | 100% | 0 |
| bge-m3 | original | 99.73% | 100% | 0 |
| bge-m3 | rewritten | 99.91% | 100% | 0 |
| kure-v1 | original | 99.64% | 100% | 0 |
| kure-v1 | rewritten | 99.64% | 100% | 0 |
| qwen3-0.6b | original | 99.91% | 100% | 0 |
| qwen3-0.6b | rewritten | 100% | 100% | 0 |

겹침 100%는 검색된 조문 집합이 같다는 뜻이다. 순서가 같거나 모든 조문이 정답이라는 뜻은 아니다. 전수 검색 점수는 기존 계산과 오차 0.00001 이내인지도 질문마다 검사했다. 동점에서는 조문 순서나 마지막 포함 조문이 달라질 수 있다.

| 모델 | 질문 | 상위 10개가 달라진 질문 수 / 110 | Recall@5가 달라진 양성 질문 수 / 100 | nDCG@10이 달라진 양성 질문 수 / 100 |
| --- | --- | ---: | ---: | ---: |
| embeddinggemma | original | 3 | 0 | 1 |
| embeddinggemma | rewritten | 3 | 0 | 0 |
| bge-m3 | original | 3 | 0 | 0 |
| bge-m3 | rewritten | 1 | 0 | 0 |
| kure-v1 | original | 3 | 0 | 0 |
| kure-v1 | rewritten | 3 | 0 | 0 |
| qwen3-0.6b | original | 1 | 0 | 0 |
| qwen3-0.6b | rewritten | 0 | 0 | 0 |

상위 조문 일부가 바뀌어도 정답 근거가 그대로 포함되면 Recall은 유지될 수 있다. 반대로 평균이 같아도 질문별 개선·악화가 상쇄될 수 있으므로 질문별 변화 수도 함께 계산했다.

이번 측정에서는 모든 구성의 질문별 Recall@5가 유지됐다.

근사 검색이 상위의 비정답 조문을 놓치면 정답이 상대적으로 앞에 나와 지표가 좋아질 수도 있으므로, 근사 검색이 본질적으로 더 정확하다는 근거로 해석하지 않는다.

## 검색 시간

| 모델 | 질문 | 방식 | 측정 수 | 클라이언트 중앙값 ms | 클라이언트 p95 ms | 서버 중앙값 ms | 서버 p95 ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| embeddinggemma | original | exact | 330 | 28.281 | 34.147 | 4.372 | 7.723 |
| embeddinggemma | original | ann | 330 | 26.246 | 32.205 | 1.997 | 4.751 |
| embeddinggemma | rewritten | exact | 330 | 28.346 | 33.614 | 4.356 | 5.889 |
| embeddinggemma | rewritten | ann | 330 | 25.873 | 29.168 | 1.922 | 2.847 |
| bge-m3 | original | exact | 330 | 28.637 | 34.822 | 5.11 | 8.261 |
| bge-m3 | original | ann | 330 | 25.7 | 30.872 | 2.083 | 4.978 |
| bge-m3 | rewritten | exact | 330 | 28.472 | 32.209 | 5.052 | 6.105 |
| bge-m3 | rewritten | ann | 330 | 25.543 | 29.573 | 1.928 | 2.777 |
| kure-v1 | original | exact | 330 | 28.57 | 34.62 | 5.067 | 8.438 |
| kure-v1 | original | ann | 330 | 25.554 | 30.03 | 2.023 | 4.745 |
| kure-v1 | rewritten | exact | 330 | 28.607 | 32.321 | 5.04 | 6.724 |
| kure-v1 | rewritten | ann | 330 | 25.71 | 29.386 | 1.987 | 2.839 |
| qwen3-0.6b | original | exact | 330 | 35.211 | 39.548 | 5.71 | 8.567 |
| qwen3-0.6b | original | ann | 330 | 31.522 | 37.143 | 1.904 | 4.426 |
| qwen3-0.6b | rewritten | exact | 330 | 36.5 | 41.138 | 6.146 | 7.879 |
| qwen3-0.6b | rewritten | ann | 330 | 32.411 | 39.602 | 1.972 | 4.119 |

중앙값은 측정값의 가운데, p95는 약 95%의 요청이 그 시간 안에 끝났다는 뜻이다. nearest-rank 방식으로 계산했다. 클라이언트 시간에는 PowerShell의 JSON 변환·HTTP·응답 해석이 포함된다. 서버 시간은 Qdrant 응답의 time 필드다. 두 값 모두 질문 임베딩·LLM 정제·답변 생성 시간을 포함하지 않는다.

기본 3회씩 순차 측정했으며 질문마다 exact와 ann의 실행 순서를 번갈아 적용했다. 처음 각 방식 한 번은 준비 요청으로 제외했다. 동시 사용자 부하 시험이 아니며 이전에 한 번 측정했던 임베딩 지연과 비교하지 않는다. 기존 C# 계산은 프로세스 내부에서 실행되므로 HTTP 검색 시간과 같은 조건의 속도 비교가 아니다.

ann은 exact=false 및 hnsw_ef=128 설정을 뜻한다. Qdrant가 실제로 모든 요청에 HNSW를 선택했다고 확인한 것은 아니다. 작은 데이터에서는 전수 탐색을 선택할 수도 있으므로 결과·시간이 비슷해도 이상이 아니다.

정제본은 대화형 어시스턴트가 작성한 고정 질문이며 블라인드 실험은 아니다. 이번 결과를 보고 질문이나 모델을 다시 조정하지 않았다. 정제 전후 범위 밖 오수용률은 [별도 비교](../../../../../model-comparison/reports/chunk-experiments/comparison.md)를 참고한다. 이번 실험은 임계값을 새로 학습하지 않았다.

세부 자료: 모델별 queries.csv(질문별 지표), top10.csv(상위 조문), latency.csv(반복 측정 원본), report.json(환경·입력 정보).
