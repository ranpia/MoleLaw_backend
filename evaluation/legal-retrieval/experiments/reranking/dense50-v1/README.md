# 현재 서비스 리랭커 적용 전후 비교

기존 `experiment/legal-embedding-evaluation` 브랜치에서 진행하는 쌍별 비교다. 기존 서비스 코드·설정과 실험 코퍼스·질문·정답·벡터를 변경하지 않는다.

**36문항 완료:** Hit@5는 20/36→22/36, Complete@5는 4/36→4/36이다. [결과 해석과 개선·악화 사례](review/interpretation.md) · [실행 원자료](reports/20261031T011428Z/README.md)를 따른다.

## 고정 조건

- 검색 기준: KURE-v1, 500자·중복 64자, 법률 쟁점 변환 질의, 코퍼스 내 36문항.
- 후보: 기존 Recall@50 결과와 동일한 서로 다른 조문 50개. Qdrant 전수 검색으로 50개 순서·Cosine 점수를 대조한 후 제목·대표 청크까지 고정한다.
- 리랭커: 현재 `ai-service-kure-1`의 `BAAI/bge-reranker-v2-m3`, revision `953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e`.
- 현재 실행 설정: CPU float32, 배치 2, 질문/문서 쌍 최대 1024토큰. 초과 입력은 기존 서버가 거절하며 실험에서 조용히 자르지 않는다.
- 입력: 서비스 `LawRerankerClient`와 같은 `제목 + 줄바꿈 + 검색 대표 청크 본문`. 리랭커에 정답·정답 여부를 보내지 않는다.
- 임계값 필터·질의 추가·재임베딩·새 후보 회수는 사용하지 않는다. 동점은 원래 dense 순위를 유지한다.

Hit@5·Complete@5를 공동 우선으로 보고 Recall@5, nDCG@5/10, MRR@10과 질문별 상승·하락을 함께 기록한다. 동일한 50개를 순서만 바꾸므로 Recall@50은 변하지 않아야 한다. 정답이 후보 50개에 없는 문제는 리랭커로 해결할 수 없다.

이 36문항은 이미 탐색한 튜닝 자료다. 새 독립 검증이나 LLM 답변 품질 검증으로 해석하지 않는다. 리랭커 HTTP 시간은 기록하지만 기존 dense 검색 시간을 재측정하지 않으므로 전체 검색 지연 개선을 주장하지 않는다. 오프라인 호출 제한은 300초로, Spring의 실서비스 요청 시간 제한을 그대로 재현하는 실험은 아니다.

## 파일

- `data/c500-legal_transformed/`: 후보 1,800개, 질의·정답, 입력 해시와 실제 서비스 실행 메타데이터. 고정 후 덮어쓰지 않는다.
- `reports/<UTC 실행 시각>/`: 실행별 요약, 질문별 전후 차이, 전체 순위와 완료 상태. 1문항 smoke는 `completed=false`다.
- `review/`: 조건 검토와 결과 해석.
- 공통 실행 코드는 `../../../scripts/`를 사용하고 응답 캐시는 Git 제외 `artifacts/reranker-dense50-v1/`에 저장한다.

## 재현

저장소 루트에서 실행한다. Qdrant 인증 키는 환경변수 또는 기존 로컬 `.env`로 제공하고 출력·커밋하지 않는다. 준비 스크립트는 이미 생성된 입력 폴더를 덮어쓰지 않는다. 현재 고정 입력으로 재실행할 때 준비 단계는 생략한다.

```powershell
.\evaluation\legal-retrieval\scripts\test-reranker-comparison.ps1
# 최초 준비에만 실행. 읽기 전용 Qdrant 요청만 사용한다.
.\evaluation\legal-retrieval\scripts\prepare-reranker-comparison.ps1
# 현재 서비스의 동일 모델·리비전과 고정 런타임을 검증하고 36문항 평가.
.\evaluation\legal-retrieval\scripts\run-reranker-comparison.ps1
# 완료 결과의 후보·정답·해시와 지표를 저장 순위에서 재계산해 대조.
.\evaluation\legal-retrieval\scripts\validate-reranker-comparison.ps1 -ReportDirectory '<reports 아래 완료한 실행 경로>'
```

실행별 새 보고서 디렉터리를 만들고, 요청·런타임 해시가 같은 응답 캐시만 재사용한다. 중단 후 재실행하면 완료한 문항은 재추론하지 않는다. HTTP/모델/응답 검증 실패를 dense 결과로 대체하거나 해당 질문을 조용히 빼지 않는다. 진행 중 `run.json`은 `completed=false`이며 36문항이 모두 끝나야 true가 된다. `queries.csv`만 남은 중간 실행을 최종 결과로 해석하지 않는다.
