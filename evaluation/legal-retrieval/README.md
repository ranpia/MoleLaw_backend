# 법령 임베딩 검색 실험

MoleLaw에서 사용자 질문에 필요한 법령 근거를 잘 찾는 검색 구성을 비교하는 실험 공간이다. 실제 법령 텍스트를 벡터로 바꾸고, 정답 조문이 정해진 질문을 검색해 모델·청크 크기·질의 표현에 따른 차이를 측정한다.

[실험별 자료](experiments/README.md) · [결과 요약](experiments/summary.md) · [실행 안내](RUNBOOK.md) · [실험 계획](PLAN.md)

## 무엇을 비교하나

| 비교 대상 | 확인하는 내용 | 자료 |
| --- | --- | --- |
| 임베딩 모델 | EmbeddingGemma·BGE-M3·KURE-v1·Qwen3-Embedding-0.6B의 검색 품질 | [모델 비교](experiments/model-comparison/reports/chunk-experiments/overview.md) |
| 청크 크기 | 같은 법령을 300·500·700자로 나눴을 때 근거 회수 차이 | [청크 비교](experiments/query-strategies/chunk-sizes-v1/reports/kure-v1/interpretation.md) |
| 질의 표현 | 원본 질문·의미 보존 정제·법률 쟁점 변환의 차이 | [질의 변환 비교](experiments/query-strategies/sentence-transform-v1/reports/kure-v1/interpretation.md) |
| 평가 범위 | 법령 범위를 넓히거나 독립 작성 질문을 사용했을 때의 차이 | [확장 검증](experiments/retrieval-validation/expanded-v1/reports/kure-v1/README.md) · [독립 질문 검증](experiments/retrieval-validation/independent-v1/reports/kure-v1/interpretation.md) |
| 후보 회수 범위 | 상위 50개에 필요한 근거가 얼마나 포함되는지 | [Recall@50 비교](experiments/query-strategies/candidate-coverage-v1/reports/model-comparison.md) |
| 서비스 리랭커 | 같은 dense 후보 50개를 현재 BGE 리랭커로 재정렬했을 때의 차이 | [리랭커 전후 비교](experiments/reranking/dense50-v1/README.md) |
| 적응형 반복 검색 | 현재 리랭커를 유지한 최초 검색 대비 추가 검색·근거 누적의 차이 | [반복 검색 비교](experiments/retrieval-validation/adaptive-v1/README.md) |

실험마다 질문셋과 코퍼스 범위가 다르므로 같은 조건의 결과끼리 비교한다. 최초 모델 비교의 점수를 독립 질문 검증이나 실제 서비스 성능으로 대체해서 해석하지 않는다.

## 어떤 자료를 사용하나

공식 법령 HTML과 필요한 PDF 별표에서 추출한 텍스트를 고정해 사용한다. 모델을 비교할 때 원문·질문·정답·분할은 같게 두고 모델이나 청크 조건을 바꾼다. HTML과 PDF 추출기의 정확도를 직접 비교하는 실험은 아니다.

최초 모델 비교는 주 법령 20개와 보충 법령 13개 등에서 얻은 원문 5,486개 단위와 질문 110개를 사용했다. 확장 검증은 주 법령 30개·원문 6,527개 단위·질문 270개로 구성했다. 독립 역할 분리 질문셋은 100문항이며, 그중 코퍼스 안에 정답 근거가 있는 36문항으로 검색 순위 지표를 계산한다. 나머지는 추가 자료 필요·지원 범위 밖·사실 부족으로 구분한다.

질문·정답·코퍼스의 버전과 해시는 각 실험의 매니페스트에 기록한다. 이미 결과를 보고 조정한 질문셋은 탐색·튜닝 자료로 취급한다.

## 어떻게 평가하나

법령명·조문명과 본문을 모델의 입력 계약에 맞게 임베딩하고 질문 벡터로 검색한다. 긴 조문이 여러 청크로 나뉘어도 정답은 조문·별표 같은 원문 단위로 센다. 같은 원문의 청크 가운데 가장 높은 유사도를 그 원문의 점수로 사용해 중복을 제거한다. 청크 비교는 조문 경계를 유지한 최대 문자 수 300·500·700자와 중복 64자를 기준으로 한다.

**Hit@5와 Complete@5를 공동 우선 지표**로 본다.

| 지표 | 쉽게 말하면 |
| --- | --- |
| Hit@5 | 상위 5개에서 필요한 정답 근거를 하나라도 찾은 질문의 비율 |
| Complete@5 | 상위 5개에서 그 질문에 필요한 정답 근거를 모두 찾은 비율 |
| Recall@5·Recall@50 | 필요한 근거 중 상위 5개·50개에서 찾은 비율을 질문별로 평균한 값 |
| MRR@10 | 첫 정답이 얼마나 앞에 나오는지 나타내는 값. 10위 밖이면 0점 |
| nDCG | 여러 정답이 검색 결과 앞쪽에 잘 배치됐는지 보는 값 |

유사도는 질문과 문서 벡터가 가까운 정도이며 정답 확률이 아니다. 서로 다른 모델의 원시 유사도를 그대로 비교해 우열을 정하지 않는다. [예시와 자세한 지표 설명](experiments/model-comparison/reports/similarity-and-metrics.md)을 참고한다.

현재 Recall@50 비교는 **점수 임계값으로 걸러내지 않은 검색 순위**를 평가한다. 과거 임계값 탐색 후보는 초기화·비활성화했으며 서비스나 고정 평가 기준값에 적용한 적이 없다. 과거 보고서의 수용률과 기준값은 당시 분석 기록이다. [임계값 분석 이력과 현재 상태](experiments/query-strategies/threshold-sweep-v1/reports/README.md)에 구분해 두었다.

## 자료는 어디에 있나

| 위치 | 역할 |
| --- | --- |
| [experiments/](experiments/README.md) | 목적별 실험과 결과 안내 |
| 각 실험의 `data/` | 고정 질문·코퍼스·매니페스트 |
| 각 실험의 `review/` | 실험 조건·검수·공식 근거·선정 이력 |
| 각 실험의 `reports/` | 완료 결과·질문별 지표·순위·비교표·그래프 |
| `scripts/` | 준비·실행·검증·집계 도구와 공통 코드 |
| [layout.json](layout.json) | 이전 논리 실험 이름과 현재 폴더의 대응 |
| `artifacts/` | 원문 스냅샷·벡터 캐시·로그. Git 제외 |

문서·질문 벡터와 검색 결과는 [실험용 Qdrant](experiments/infrastructure/qdrant/README.md)에서 관리한다. Git에는 재현과 공유에 필요한 고정 입력·결과를 남기고, 로컬 캐시는 같은 입력의 재추론을 줄이는 데 사용한다. 고정 매니페스트 내부의 옛 경로는 해시 보존을 위해 출처 기록으로 유지하며 실행 도구가 현재 위치로 연결한다.

## 결과를 해석할 때

이 실험은 필요한 근거를 찾는 검색 평가다. LLM의 답변 정확도·근거 충분성 판단·자동 법령 수집·서비스 배포 준비를 검증한 결과는 아니다. `evaluationReady=true`도 고정 입력으로 검색 평가를 실행할 준비가 됐다는 뜻이다.

정답 목록에 대체 가능한 관련 조문이 빠질 수 있고, 작은 표본에서는 한두 문항 차이로 비율이 크게 바뀐다. 원문을 보며 만든 질문의 결과와 독립 작성 질문의 결과를 구분해 읽는다. 과거 모델 선정 당시의 개발용 Recall@5 기준도 당시 기록으로 보존한다. 구체적인 수치와 한계는 각 실험 결과 문서를 따른다.
