# 법률 질의 변환과 300·500·700자 청크 비교

2026-10-20, 사용자 요청에 따라 기존 300자 결과에 500자·700자를 추가한다. 질문을 단어 목록으로 바꾸는 실험은 진행하지 않는다. 같은 원본 100문항·의미 보존 정제·법률 쟁점 중심 문장형 변환·고정 정답을 유지한다.

## 고정 범위

주 법령 30개와 보충 자료의 원문 6,527개 단위를 기존 300자 코퍼스에서 겹침·원문 SHA·메타데이터 일치를 검증하며 복원했다. 원문 텍스트와 조문 ID·제목·출처·버전은 세 크기에서 동일하다. 중복은 64자로 유지하고 최대 청크 크기만 300 → 500 → 700자로 바꾼다.

기존 300자 코퍼스에는 legacy 자료의 공백 경계 분할과 `final-v1:` 추가 자료의 고정 문자 간격 분할이 함께 쓰였다. 각 원문 단위의 기존 규칙을 500·700자에서도 유지한다. 이는 모든 조문을 단일한 새로운 분할 알고리즘으로 바꾸는 실험이 아니다. 원문 수집·파싱·질문·정답·질의 변환을 변경하지 않는다.

| 청크 최대 문자 | 청크 수 | 원문 단위 |
| ---: | ---: | ---: |
| 300 | 10,999 | 6,527 |
| 500 | 8,286 | 6,527 |
| 700 | 7,396 | 6,527 |

KURE-v1 revision `8b418a58414668e75532ed045c22d9ca018ae2b2`, 1024차원, 문서/질문 입력 형식과 런타임을 유지한다. 같은 질문 벡터 300개를 Qdrant에서 읽어 세 크기에 사용하며 질문을 다시 임베딩하지 않는다. 원본·기존 정제·법률 쟁점 변환의 각 100개 결과를 크기별로 집계한다. 기존 300자의 300개 결과는 재사용하고 500·700자에서 600개 검색을 추가한다.

정답 지표는 코퍼스 내 36문항에 대해 조문 단위 Recall@5/10·Hit·Complete·MRR@10·nDCG로 집계한다. 다른 64문항은 점수 수용률만 기록한다. 임계값은 각 질의 모드의 기존 값을 유지한 진단이며 크기별로 다시 최적화하지 않는다.

## 실행과 자원

새 문서 코퍼스·원문 정규 identity·질문·변환·300자 결과 해시는 [고정 manifest](../../data/legal-query-chunks-v1/manifest.json)에 묶었다. 독립 코드 리뷰 후 원문 보존·재청크 재현·입력 해시·300자 기존 집계를 오프라인 검증했고, Qdrant 질문 벡터 300개와 provenance·공용 문서 캐시를 모델 호출 없이 확인했다.

당시 캐시에서 추가 생성이 필요한 입력은 500자 1,206개, 700자 최대 919개였다. 500자 처리 후 새로 생긴 같은 입력은 700자에서도 재사용하므로 실제 새 입력 수는 더 적을 수 있다. 모델 추론은 별도 터미널 하나에서 CPU 6개·메모리 8GB로 순차 실행한다. 완료한 배치의 캐시는 즉시 보존하고, 500자 결과는 700자 시작 전에 저장한다.

```powershell
# 이미 고정된 입력 검증
.\evaluation\legal-retrieval\scripts\prepare-legal-query-chunks.ps1 -ValidateOnly
.\evaluation\legal-retrieval\scripts\run-kure-legal-query-chunks.ps1 -ValidateOnly
# 필요 시 Qdrant/캐시 사전 검사: 모델 호출 없음
.\evaluation\legal-retrieval\scripts\run-kure-legal-query-chunks.ps1 -CheckQdrantOnly
# 실제 순차 실행
$runner=Join-Path $PWD 'evaluation/legal-retrieval/scripts/run-kure-legal-query-chunks.ps1'
Start-Process powershell.exe -ArgumentList @('-NoExit','-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"')) -WorkingDirectory $PWD.Path
```

로그는 Git 제외 `artifacts/logs/kure-legal-query-chunks-v1-*.log`, 중지 플래그는 `artifacts/control/stop-kure-legal-query-chunks-v1.flag`다. 사용자 요청 중지 후 재개할 때는 명시적으로 중지를 해제한 뒤 해당 파일을 제거한다. 실패·완료 시 KURE를 중지하며 기존 실험 DB는 보존한다. 실행 후 반복 모니터링하지 않는다.

결과는 `reports/legal-query-chunks-v1/kure-v1/`에 보존한다. `char500`·`char700`에는 각 300개 질문·3,000개 순위 행과 집계가 저장되며, 모두 완료하면 기존 300자를 합친 900개 질문·9,000개 순위 행의 통합 결과를 저장한다. 문서·질문 벡터·검색 결과는 크기별 별도 Qdrant 컬렉션에 둔다.

## 해석의 한계

이 질문셋은 이미 결과와 임계값을 보고 전략을 설계한 튜닝 자료다. 질문과 정답을 고정해도 새로운 독립 held-out 평가가 되지 않는다. 크기 변경 효과를 확인하고 실제 답변 정확도·서비스 LLM 변환 품질·근거 충분성 판단·운영 최적값으로 확대 해석하지 않는다. 원문과 질문을 보고 만든 고정 정답의 대체 근거 누락 가능성도 유지한다. 서비스 준비 상태는 false다.

## 실행 완료

500자 평가·저장 후 700자를 순차 평가했고 통합 900개 질문·9,000개 순위 행·36개 집계를 보존했다. 실제 신규 문서 입력은 500자 1,206개·700자 680개였다. 모델은 완료 후 중지했다. [집계](../../reports/legal-query-chunks-v1/kure-v1/README.md)와 [그래프·해석](../../reports/legal-query-chunks-v1/kure-v1/interpretation.md)을 참고한다.
