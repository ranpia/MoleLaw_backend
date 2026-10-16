# KURE-v1 CPU 평가 환경

[고려대 공식 KURE-v1](https://huggingface.co/nlpai-lab/KURE-v1)을 Sentence Transformers로 실행하는 로컬 실험 환경이다. 애플리케이션 서버나 Ollama 서버가 아니며, 기존 법령 평가기가 사용하는 최소 HTTP 계약만 제공한다. 공개 법령·작성 질문을 사용하고 외부 추론 API로 전송하지 않는다. 첫 시작 때 공식 모델 파일을 Hugging Face에서 다운로드한다.

- 공식 revision: `8b418a58414668e75532ed045c22d9ca018ae2b2`
- Sentence Transformers 6.1.0 / Transformers 5.20.0 / PyTorch 2.14.1 CPU
- 출력 1,024차원, CLS pooling 및 정규화, float32, 최대 8,192토큰
- CPU 6개, threads 6, 메모리 8GiB, GPU 없음, 순차 요청, 배치 16개
- PyTorch 패딩 비용을 줄이기 위해 신규 문서 입력을 문자 길이 순으로 묶는다. 입력 내용·배치 크기·정답·검색 지표는 동일하다. 기존 정상 벡터도 재사용한다.
- 호스트 `127.0.0.1:11436`에만 게시. 모델은 별도 `kure_models` 볼륨에 저장

```powershell
docker compose -f docker/kure/compose.yml up -d --build
docker compose -f docker/kure/compose.yml logs --tail 30
# 모델 다운로드·로딩 후 healthy 상태에서 실행한다.
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-model.ps1 -Model kure-v1 -Port 11436
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-results.ps1
# 결과 저장 이후 컨테이너만 종료하고 모델 볼륨은 보존한다.
docker compose -f docker/kure/compose.yml stop
```

다른 모델 평가와 동시에 실행하지 않는다. `docker compose down -v`는 다운로드한 모델을 지우므로 일반 중단에 사용하지 않는다. 문서 및 질문은 KURE-v1로 다시 임베딩하며 기존 BGE-M3 벡터 공간을 재사용하지 않는다. 정확히 같은 KURE 입력만 전용 공유 캐시에서 재사용한다.

2026-10-16 사용자 요청으로 300문자까지만 마무리하고 700문자는 보류한다. `evaluation/legal-retrieval/artifacts/control/stop-kure-v1.flag`가 있으면 다음 구성을 실행하지 않는다. 이미 실행 중인 구성은 완료하고 결과를 보존한다. 사용자가 재개를 지시한 뒤에만 이 파일을 제거하고 필요한 크기를 지정한다.

```powershell
# 사용자 재개 지시 이후에만 실행
Remove-Item -LiteralPath evaluation/legal-retrieval/artifacts/control/stop-kure-v1.flag
& evaluation/legal-retrieval/scripts/run-model.ps1 -Model kure-v1 -Port 11436 -Sizes @(700)
```

평가기 계약은 GET `/api/version`, `/api/tags`, `/api/ps`, POST `/api/embed`다. `/api/embed`는 `model`, 문자열 배열 `input`, `truncate=false`, `options.num_thread=6`, `options.num_gpu=0`를 받고 `embeddings` 배열을 반환한다. 잘못된 모델·자원 설정·긴 입력·잘못된 배치는 400으로 거절한다. 빈 입력과 `keep_alive=0`은 모델을 언로드한다. 그 외 Ollama 기능, 모델 교체·다운로드 엔드포인트는 제공하지 않는다.

이미지 빌드에서 `test_server.py`의 오프라인 HTTP 계약 테스트를 실행한다. 실제 모델의 출력 차원·유한값·0 벡터·단위 정규화 및 검색 평가는 별도 실행으로 확인한다. 원본 Sentence Transformers 출력과 어댑터 응답의 일치는 실제 모델 검증 단계에서 확인한다.

```powershell
docker compose -f docker/kure/compose.yml exec -T kure python /app/verify_model.py
```

이 검증은 고정 revision의 공식 모델을 독립 프로세스에서 불러와 한국어 입력 4개를 비교한다. 검증 중에는 모델 평가를 동시에 실행하지 않는다. 다운로드 정체를 피하기 위해 Compose는 공식 `HF_HUB_DISABLE_XET=1` 옵션으로 일반 다운로드를 사용한다.

하드웨어와 threads/batch를 맞추지만 Ollama의 Gemma BF16·BGE F16과 KURE의 PyTorch float32는 런타임·정밀도가 다르다. 검색 성능과 실행 조건을 함께 기록하고, warm 질문 지연을 동일 구현의 순수 모델 속도 차이로 단정하지 않는다. 캐시가 있는 색인 시간은 최초 전체 색인 성능 지표가 아니다.
