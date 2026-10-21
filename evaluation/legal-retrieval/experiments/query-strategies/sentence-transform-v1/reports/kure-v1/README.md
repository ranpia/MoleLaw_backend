# KURE-v1 법률 쟁점 중심 질의 변환 비교

KURE 1024차원·300자·중복 64자와 기존 10,999개 문서 청크를 고정했다. 기존 100문항의 원본·의미 보존 정제·법률 쟁점 중심 변환을 비교한다. 원본·정제 200건은 고정 결과를 읽기만 하며 신규 변환 100건만 추론한다. 문서 재색인은 하지 않는다.

| 질문 | 분류 | 문항 | Recall@5 | Complete@5 | MRR@10 | 점수 수용률 |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| original | in_corpus | 36 | 14.12% | 8.33% | 0.1755 | 19.44% |
| original | missing_corpus | 44 | - | - | - | 9.09% |
| original | unsupported | 15 | - | - | - | 20.00% |
| original | needs_clarification | 5 | - | - | - | 20.00% |
| rewritten | in_corpus | 36 | 16.44% | 8.33% | 0.1463 | 27.78% |
| rewritten | missing_corpus | 44 | - | - | - | 6.82% |
| rewritten | unsupported | 15 | - | - | - | 13.33% |
| rewritten | needs_clarification | 5 | - | - | - | 60.00% |
| legal_transformed | in_corpus | 36 | 28.70% | 8.33% | 0.3540 | 63.89% |
| legal_transformed | missing_corpus | 44 | - | - | - | 25.00% |
| legal_transformed | unsupported | 15 | - | - | - | 13.33% |
| legal_transformed | needs_clarification | 5 | - | - | - | 20.00% |

Recall은 정답 근거 중 찾은 비율, Complete는 근거를 모두 찾은 문항 비율이다. MRR@10은 첫 정답 순위의 역수이며 10위 밖은 0이다. in_corpus 외 세 분류에는 정답 순위 지표를 계산하지 않는다.

점수 수용률은 상위 점수가 고정 임계값을 넘은 비율이다. 원본과 기존 정제는 기존 임계값을 유지하고 legal_transformed는 기존 정제 임계값 0.598293414660874를 진단용으로 사용한다. 임계값 최적화는 수행하지 않았다. 서비스 LLM의 근거 충분성 판단, 답변 생성, 자동 수집 정확도가 아니다.

기존 결과를 알고 설계한 탐색·튜닝 평가이며 새 질문을 사용하지 않았다. 에이전트 질의 작성·검수 역할을 나누었지만 독립 검증셋이 아니며 서비스 성능 확정 근거가 아니다. 실제 서비스 LLM 프롬프트의 품질도 평가하지 않았다.

[고정 입력](../../data/manifest.json) · [기존 독립 검증](../../../../retrieval-validation/independent-v1/reports/kure-v1/README.md) · [선정 근거](../../../../model-comparison/review/model-selection.md)

[그래프·문항별 효과·임계값 해석](interpretation.md)을 함께 참고한다.
