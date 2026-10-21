# Qwen3-Embedding CPU 실험

공식 `Qwen/Qwen3-Embedding-0.6B` revision `97b0c614be4d77ee51c0cef4e5f07c00f9eb65b3`을 사용한다. Apache-2.0 라이선스의 다국어 모델이며 한국어 전용 파인튜닝 모델은 아니다. 원래 1024차원, 32768 토큰 문맥, 마지막 토큰 풀링과 L2 정규화를 사용한다. 이번 실험은 차원을 줄이지 않는다. 청크별 완료 상태는 [Qwen3 결과 안내](../../evaluation/legal-retrieval/experiments/model-comparison/reports/qwen3-0.6b/README.md)와 통합 비교표를 기준으로 확인한다.

질문 입력은 고정 영문 지시문 `Instruct: Given a legal question in Korean, retrieve relevant Korean legal provisions that answer the question.\nQuery:{text}`이며 문서는 `{title}\n{text}`다. 정제 여부와 무관하게 같은 지시문을 쓴다. 공식 권장 형식에 맞춘 것으로, 다른 모델과 입력 문자열이 같다는 뜻은 아니다.

이미 설치된 `molelaw/kure-embedding:st6.1.0-cpu` 이미지의 라이브러리를 재사용한다. KURE 체크포인트는 사용하지 않는다. CPU 6개, 메모리/스왑 8GB, 로컬 포트 11437, 별도 모델 볼륨으로 격리한다. 다른 모델 추론과 동시에 실행하지 않는다.

```powershell
# 기존 컨테이너가 있는 환경에서, 요청한 크기 하나를 별도 터미널로 실행
$runner = Join-Path $PWD 'evaluation/legal-retrieval/scripts/run-qwen-terminal.ps1'
# 사용자가 재개를 요청한 경우 이전 완료 시 남긴 중지 플래그 해제
Remove-Item -LiteralPath 'evaluation/legal-retrieval/artifacts/control/stop-qwen3-0.6b.flag' -ErrorAction SilentlyContinue
Start-Process powershell.exe -ArgumentList @('-NoExit','-NoProfile','-ExecutionPolicy','Bypass','-File',('"'+$runner+'"'),'-Size','700') -WorkingDirectory $PWD.Path
```

`-Size`는 300·500·700 중 하나다. 실행기는 컨테이너 시작, 원본·정제 질문 평가, Qdrant 적재·검증, 비교표·그래프 갱신 후 컨테이너 종료까지 수행한다. Git 커밋·푸시는 수행하지 않는다. 로그는 `evaluation/legal-retrieval/artifacts/logs/`에 남고, 이전 실행의 로그는 `artifacts/`에 남아 있을 수 있다. 이미 열린 실행 터미널이 있으면 중복 실행하지 않는다.

새 환경에서는 기존 수동 컨테이너가 없을 때만 아래 초기 구성을 사용한다.

```powershell
docker build -t molelaw/kure-embedding:st6.1.0-cpu docker/kure
docker volume create molelaw_qwen3_models
docker compose -f docker/qwen3/compose.yml up -d
docker compose -f docker/qwen3/compose.yml exec qwen3 python /qwen/verify_model.py
```

`verify_model.py`는 모델을 하나씩 로드해 공식 SentenceTransformer 출력과 어댑터 출력, 1024차원, 정규화를 검증한다. HTTP 계약은 `test_server.py`의 7개 오프라인 검사로 검증한다. 컨텍스트 초과 입력은 자동 자르기 대신 거절한다.

자료·벡터는 모델/청크별 법령 컬렉션, 질문·정답·결과는 모델별 `molelaw_eval_queries_*` 컬렉션에 저장한다. 두 종류를 분리해 질문 벡터가 법령 검색에 섞이지 않게 한다. Git 문서는 공유용 결과이며 로컬 캐시는 재추론 방지와 재현용이다.

고정 데이터셋과 정제본·모델 설정은 `store-qdrant-catalog.ps1`로 Qdrant 카탈로그에 저장한다. 질문 JSON·정제 JSON은 원본 바이트도 보존하고 SHA-256 왕복 일치를 검사한다. `search-qdrant-evaluation.ps1`는 모델 서버와 로컬 벡터 캐시 없이 Qdrant에 저장한 질문으로 재검색한다.

공식 근거: https://huggingface.co/Qwen/Qwen3-Embedding-0.6B
