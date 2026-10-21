# Recall@50 모델 비교

[완료 모델 비교표와 그래프](reports/model-comparison.md)에서 KURE와 BGE의 결과를 함께 볼 수 있다. BGE-M3는 300·500·700자 평가를 완료했다. Qwen은 정리 시점에 최종 보고서가 없어 실행 중으로 구분하고 비교에서 제외했다.

법률 쟁점 변환 기준 BGE의 Hit@5는 300·500·700자에서 17·17·18/36, Complete@5는 3·2·1/36이다. KURE는 각각 Hit@5 18·20·20/36, Complete@5 3·4·3/36이다. 이 표본에서는 KURE가 모든 크기의 Hit@5에서 앞서고 Complete@5도 같거나 높다. BGE의 상위 50개 후보 Recall은 약 55%로 KURE의 약 55~57%와 가깝지만 상위 5개 지표가 같다는 의미는 아니다.

BGE는 700자에서 Hit@5가 가장 높고 300자에서 Complete@5가 가장 높아 단일 크기를 종합 우승으로 정하지 않는다. 기존 후속 실험 기준 KURE 500자를 변경할 근거는 이번 BGE 결과에서 확인되지 않았다. 질문이 36개인 작은 튜닝 표본이라는 한계를 유지한다.

KURE-v1에 사용한 독립 질문의 코퍼스 내 36문항·동일 정답·원본/기존 정제/법률 쟁점 변환 질의·300/500/700자 코퍼스로 BGE-M3와 Qwen3-Embedding-0.6B를 비교한다. 모델별 324회 검색, 상위 50개 조문 순위 16,200개다. Hit@5·Complete@5가 공동 우선이고 Recall@50·Hit@50·Complete@50·MRR@10은 함께 보고한다.

임계값 탐색 후보는 비활성화했다. 이번 비교에는 점수 필터나 임계값 최적화를 사용하지 않으며 음성 수용률도 평가하지 않는다. 기존 고정 기준값이나 서비스 설정은 변경하지 않았다. 이 질문셋은 이미 탐색한 튜닝 자료이며 새 독립 검증으로 해석하지 않는다.

- [KURE 완료 결과](reports/kure-v1/README.md)는 기존 기록 그대로 유지한다.
- BGE-M3 결과 위치: `reports/bge-m3/`
- Qwen 결과 위치: `reports/qwen3-0.6b/`

완료된 보고서를 다시 정리하려면 `./evaluation/legal-retrieval/scripts/summarize-recall50.ps1`을 실행한다. 저장된 상위 50개 순위에서 지표를 재계산하고 완료 모델만 비교 CSV·Markdown·SVG에 반영한다. 모델 추론과 실행 중 작업에는 관여하지 않는다.

후속 모델의 `manifest.json`은 시작 시 작성하고 `queries.csv`, `top50.csv`, `summary.csv`는 청크 크기별 완료 시 저장한다. 최종 `report.json`이 있어야 모델 전체 완료다. 저장된 모델 입력별 캐시를 검증해 재사용하고 부족한 문서·질의 벡터만 생성한다. Qdrant에 문서·질의·검색 결과를 보존하고 의미별 별칭도 추가한다.

실행 명령은 저장소 루트에서 `./evaluation/legal-retrieval/scripts/run-recall50-terminal.ps1`이다. BGE→Qwen 순차 실행, CPU 6·RAM 8GB 컨테이너 하나씩 사용한다. 오류가 발생하면 다음 모델을 시작하지 않는다. 완료된 캐시는 다음 실행에 재사용한다. 멈추려면 `evaluation/legal-retrieval/artifacts/control/stop-recall50-models-v1.flag` 파일을 만들면 현재 API 작업 후 중단하고 컨테이너를 정지한다. 재개할 때 이 플래그를 제거한다. 실행 로그는 `artifacts/logs/recall50-bge-qwen-<시각>.log`에 남는다.
