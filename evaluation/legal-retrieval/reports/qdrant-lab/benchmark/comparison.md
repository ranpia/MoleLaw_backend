# Qdrant 전체 질문 검색 비교

700문자 청크에서 모델별 원본 질문 110개와 고정 정제 질문 110개를 실행했다. 같은 질문 벡터로 local(기존 C# 전수 계산), exact(Qdrant 전수), ann(Qdrant 근사 설정)을 비교했다. 컬렉션과 문서 벡터는 수정하지 않았다.

original은 원본 질문, rewritten은 정제 질문이다. development는 개발용 양성 30개, held_out은 검증용 양성 70개다. 정답 없는 질문은 품질 지표에서 제외하고 시간·검색 일치율에는 포함했다.

## 검색 품질

| 모델 | 질문 | 분할 | 방식 | Recall@5 | Recall@10 | 1위 적중률 | Complete@5 | MRR@10 | nDCG@5 | nDCG@10 |
| --- | --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| embeddinggemma | original | development | local | 64.22% | 71% | 46.67% | 50% | 0.5958 | 0.5543 | 0.5821 |
| embeddinggemma | original | development | exact | 64.22% | 71% | 46.67% | 50% | 0.5958 | 0.5543 | 0.5821 |
| embeddinggemma | original | development | ann | 64.22% | 71% | 46.67% | 50% | 0.5958 | 0.5543 | 0.5821 |
| embeddinggemma | original | held_out | local | 78.14% | 83.52% | 62.86% | 65.71% | 0.739 | 0.7077 | 0.7319 |
| embeddinggemma | original | held_out | exact | 78.14% | 83.52% | 62.86% | 65.71% | 0.739 | 0.7077 | 0.7319 |
| embeddinggemma | original | held_out | ann | 78.14% | 83.52% | 62.86% | 65.71% | 0.739 | 0.7077 | 0.7319 |
| embeddinggemma | rewritten | development | local | 72% | 84.06% | 60% | 53.33% | 0.7117 | 0.6451 | 0.6952 |
| embeddinggemma | rewritten | development | exact | 72% | 84.06% | 60% | 53.33% | 0.7117 | 0.6451 | 0.6952 |
| embeddinggemma | rewritten | development | ann | 72% | 84.06% | 60% | 53.33% | 0.7117 | 0.6451 | 0.6952 |
| embeddinggemma | rewritten | held_out | local | 78.43% | 87.1% | 65.71% | 62.86% | 0.7711 | 0.7211 | 0.7576 |
| embeddinggemma | rewritten | held_out | exact | 78.43% | 87.1% | 65.71% | 62.86% | 0.7711 | 0.7211 | 0.7576 |
| embeddinggemma | rewritten | held_out | ann | 78.43% | 87.57% | 65.71% | 62.86% | 0.7711 | 0.7211 | 0.7596 |

MRR@10은 첫 정답이 10위 밖이면 0으로 처리한다. 이전 보고서의 전체 순위 MRR과 정의가 다르므로 직접 같은 수치로 비교하지 않는다. Hit@5·Precision@5도 [CSV](comparison.csv)에 보존했다.

## 검색 결과가 얼마나 같은가?

| 모델 | 질문 | ANN·전수 상위 10개 겹침 | 기존 계산·Qdrant 전수 겹침 | 과거 Recall@5가 달라진 질문 수 |
| --- | --- | ---: | ---: | ---: |
| embeddinggemma | original | 99.73% | 100% | 0 |
| embeddinggemma | rewritten | 99.55% | 100% | 0 |

겹침 100%는 검색된 조문 집합이 같다는 뜻이다. 순서가 같거나 모든 조문이 정답이라는 뜻은 아니다. 전수 검색 점수는 기존 계산과 오차 0.00001 이내인지도 질문마다 검사했다. 동점에서는 조문 순서나 마지막 포함 조문이 달라질 수 있다.

## 검색 시간

| 모델 | 질문 | 방식 | 측정 수 | 클라이언트 중앙값 ms | 클라이언트 p95 ms | 서버 중앙값 ms | 서버 p95 ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: |
| embeddinggemma | original | exact | 330 | 26.899 | 32.286 | 3.717 | 6.373 |
| embeddinggemma | original | ann | 330 | 25.118 | 29.961 | 1.712 | 3.874 |
| embeddinggemma | rewritten | exact | 330 | 27.346 | 35.952 | 3.758 | 5.54 |
| embeddinggemma | rewritten | ann | 330 | 25.434 | 32.665 | 1.695 | 2.902 |

중앙값은 측정값의 가운데, p95는 약 95%의 요청이 그 시간 안에 끝났다는 뜻이다. nearest-rank 방식으로 계산했다. 클라이언트 시간에는 PowerShell의 JSON 변환·HTTP·응답 해석이 포함된다. 서버 시간은 Qdrant 응답의 time 필드다. 두 값 모두 질문 임베딩·LLM 정제·답변 생성 시간을 포함하지 않는다.

기본 3회씩 순차 측정했으며 질문마다 exact와 ann의 실행 순서를 번갈아 적용했다. 처음 각 방식 한 번은 준비 요청으로 제외했다. 동시 사용자 부하 시험이 아니며 이전에 한 번 측정했던 임베딩 지연과 비교하지 않는다. 기존 C# 계산은 프로세스 내부에서 실행되므로 HTTP 검색 시간과 같은 조건의 속도 비교가 아니다.

ann은 exact=false 및 hnsw_ef=128 설정을 뜻한다. Qdrant가 실제로 모든 요청에 HNSW를 선택했다고 확인한 것은 아니다. 작은 데이터에서는 전수 탐색을 선택할 수도 있으므로 결과·시간이 비슷해도 이상이 아니다.

정제본은 대화형 어시스턴트가 작성한 고정 질문이며 블라인드 실험은 아니다. 이번 결과를 보고 질문이나 모델을 다시 조정하지 않았다. 정제 전후 범위 밖 오수용률은 [별도 비교](../../query-rewrite-v1/comparison.md)를 참고한다. 이번 실험은 임계값을 새로 학습하지 않았다.

세부 자료: 모델별 queries.csv(질문별 지표), top10.csv(상위 조문), latency.csv(반복 측정 원본), report.json(환경·입력 정보).
