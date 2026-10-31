# 현재 리랭커를 유지한 반복 검색 비교

현재 서비스의 질문 분석·근거 읽기·충분성 판단·재검색·후보 누적 코드와 프롬프트를 복사해, 최초 검색 직후 후보와 적응형 검색 종료 시 후보를 문항별로 비교한다. 이전 리랭커 실험과 같은 36문항·500자/64자 중복 코퍼스·KURE-v1·BGE 리랭커를 사용한다.

- [비교 계약과 한계](review/protocol.md)
- [결과 해석](review/interpretation.md): 36문항 완료. Hit@15 52.78%→72.22%, Complete@15 13.89%→22.22%.
- [실행 결과](reports/full-serviceids-20261031/README.md): `run.json.completed=true`
- 실행기: [run-adaptive-comparison.ps1](../../../scripts/run-adaptive-comparison.ps1)
- 실제 서비스 호출 하네스: [AdaptiveRetrievalComparison.java](../../../scripts/java/AdaptiveRetrievalComparison.java)
- 지표 재계산 및 추적 검증: [summarize-adaptive-comparison.ps1](../../../scripts/summarize-adaptive-comparison.ps1)
- 평가 계약 검사: [test-adaptive-comparison.ps1](../../../scripts/test-adaptive-comparison.ps1)

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-adaptive-comparison.ps1 -QuestionLimit 36
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/summarize-adaptive-comparison.ps1 -ReportDirectory <새 실행 보고서 경로>
```

실행에는 현재 서비스의 OpenAI·Qdrant 자격증명과 로컬 KURE/BGE 서버가 필요하며 실제 모델 호출 비용이 발생한다. 실행기는 자격증명을 보고서에 저장하지 않는다. 서비스·기존 벡터·고정 질문·기존 결과를 수정하지 않는다. 소스 복사본과 컴파일 결과는 Git에서 제외된 `artifacts/adaptive-comparison-v1/snapshots/`에 둔다.

`smoke-20261031`은 하네스 컴파일 실패, `smoke2-20261031`은 임베딩 요청 옵션 누락을 발견한 준비 실행이다. `smoke3-20261031`은 한 문항의 연결 검증이다. 이 실행들은 전체 실험의 집계에 포함하지 않는다.

`full-20261031`은 6문항을 기록한 뒤 조문 지정 필터와 서비스 형식 근거 ID의 차이를 발견해 중단했다. vector-only 진단 자료로만 보존하며 최종 비교에서 제외한다. `full-canonical-20261031`은 별표·고시의 별도 ID 형식을 처리하기 전, 모델 호출 없이 중단된 준비 실행이다. 최종 비교에는 `full-serviceids-20261031`만 사용한다.
