# KURE-v1 확대 최종 검증

모델·1024차원·300자·중복 64자·기존 수용 기준값을 고정했다. 기존 110문항은 회귀 확인, 새 160문항은 신규 영역 확인으로 구분한다. 30개 주 법령과 기존 보충 근거를 포함한다.

| 질문 | 범위 | 양성 | 음성 | Recall@5 | Complete@5 | MRR@10 | 음성 오수용률 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| original | legacy_development | 30 | 5 | 57.11% | 43.33% | 0.6050 | 20.00% |
| original | legacy_held_out | 70 | 5 | 71.71% | 57.14% | 0.6532 | 40.00% |
| original | expanded_held_out | 120 | 40 | 96.25% | 95.83% | 0.8784 | 27.50% |
| rewritten | legacy_development | 30 | 5 | 75.17% | 56.67% | 0.7031 | 0.00% |
| rewritten | legacy_held_out | 70 | 5 | 80.52% | 65.71% | 0.7306 | 40.00% |
| rewritten | expanded_held_out | 120 | 40 | 99.17% | 98.33% | 0.9472 | 27.50% |

사전 고정 검색 기준 통과: **True**. 각 항목: {"legacyRecallRetention":true,"rewriteNoRegression":true,"newRecallAt5":true,"newCompleteAt5":true}

신규 양성은 단일 근거 100개와 다중 근거 20개다. 음성은 외국 관할 20개, 제공되지 않은 개인 사건 사실 20개다. 세부 분포는 strata.csv를 참고한다. MRR@10은 기존 전체 순위 MRR과 직접 비교하지 않는다. 질문과 정제본은 공식 원문을 본 어시스턴트가 작성해 독립 블라인드 검증은 아니다. 법령별 표본은 무작위가 아니며 신규 영역 점수와 기존 점수의 차이를 모델 변경 효과로 해석하지 않는다.

음성 오수용률은 검색 점수의 기준값 통과율이다. 실제 답변의 안전성이나 거절 성능을 검증한 것은 아니다. serviceReady=false를 유지한다. 결과가 미달해도 이 데이터로 질문·정답·청크·기준값을 다시 조정하지 않는다.

[선정 근거](../../../../model-comparison/review/model-selection.md) · [고정 입력](../../data/manifest.json)
