# KURE-v1 독립 질문 검증

KURE 1024차원·300자·중복 64자와 기존 문서 컬렉션을 고정했다. 독립 작성과 별도 정답 검토를 거친 100문항의 원본·정제본을 비교한다. 문서 재색인은 수행하지 않는다.

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

Recall은 정답 근거 중 찾은 비율, Complete는 근거를 모두 찾은 문항 비율이다. MRR@10은 첫 정답 순위의 역수이며 10위 밖은 0이다. in_corpus 이외에는 정답 순위 지표를 계산하지 않는다.

점수 수용률은 기존 개발 데이터의 고정 임계값을 상위 검색 점수가 넘은 비율이다. 누락 법령·지원 범위 밖·사실관계 부족을 판단하는 서비스 LLM 정확도가 아니다. 자동 추가 수집과 답변 생성도 평가하지 않았다. 에이전트 역할 분리는 사람 또는 실제 사용자 평가와 같은 독립성을 보장하지 않는다.

[고정 입력](../../../data/independent-validation-v1/manifest.json) · [선정 근거](../../../review/model-selection.md)

[결과 해석·실패 예시·정답지 한계](interpretation.md)를 함께 참고한다. 현재 고정 정답 기준 성능은 서비스 적용을 확정하기에 부족하다.
