# KURE 단일 벡터 서비스

KURE-v1 고정 revision·1024차원·float32·정규화와 `/api/embed` 계약을 유지한다. CPU/CUDA 실행만 선택하며 청킹은 기존 300 코드포인트·64 중첩이다. HTTP 상태 조회는 병렬 처리하고 추론·모델 해제는 하나의 잠금으로 직렬화한다. 기본 대기열은 4개, 대기는 5초이며 포화/시간 초과는 503이다.

## 실행

공개 Python 이미지에서 고정 PyTorch·Sentence Transformers·Transformers 버전을 설치하므로 특정 PC의 선행 이미지가 필요하지 않다. 첫 실행에는 인터넷과 모델 다운로드 공간이 필요하며 캐시는 named volume에 유지한다. 기본 CPU 실행:

```powershell
docker compose -f docker/kure/compose.yml up -d --build
```

선택적 CUDA 실행은 [Docker WSL2 NVIDIA GPU 지원](https://docs.docker.com/desktop/features/gpu/)을 준비한 뒤 override를 함께 지정한다. CUDA를 요청했는데 사용할 수 없으면 시작을 실패시킨다.

```powershell
docker compose -f docker/kure/compose.yml -f docker/kure/compose.gpu.yml up -d --build
```

GPU 제품/PC 이름을 코드에 고정하지 않는다. 추가 조정은 `.env.example`을 `docker/kure/.env`로 복사하고 명령에 `--env-file docker/kure/.env`를 추가한다. 기존 파일은 덮어쓰지 않는다.

| Compose 환경변수 | CPU 기본 | GPU 기본 |
| --- | --- | --- |
| `KURE_SERVICE_PORT` | 11437 | 11437 |
| `KURE_MODEL_VOLUME` | molelaw-kure-models | 동일 캐시 |
| `KURE_THREADS` | 6 | 6 |
| `KURE_BATCH_SIZE` | 16 | 4 |
| `KURE_CPUS` | 6.0 | 4.0 |
| `KURE_MEMORY_LIMIT` | 8g | 4g |
| `KURE_QUEUE_CAPACITY` | 4 | 4 |
| `KURE_QUEUE_WAIT_SECONDS` | 5 | 5 |
| `KURE_START_PERIOD` | 10m | 10m |
| `KURE_GPU_DEVICE_ID` | 사용 안 함 | 0 |

자원 값은 조정 가능한 초기 상한이다. 시작 유예시간은 첫 모델 다운로드 시간을 포함하며 `KURE_START_PERIOD`로 조정한다. 긴 입력에서 CUDA OOM이 발생하면 503을 반환하고 추론 허가를 반환한다. 배치를 줄여 측정하며 입력을 조용히 잘라내지 않는다. 모델은 float32이고 TF32는 비활성화한다. FP16은 별도 품질 평가 대상이며 CPU/GPU 결과의 비트 단위 일치는 보장하지 않는다. 이전 PC 캐시를 재사용하려면 `KURE_MODEL_VOLUME`에 해당 볼륨 이름을 설정한다.

Spring 루트 `.env`는 Compose 설정과 별개다. `APP_LAW_VECTOR_EMBEDDING_BASE_URL=http://localhost:11437`과 `APP_LAW_VECTOR_THREADS`를 서버에 맞춘다. CPU는 `APP_LAW_VECTOR_GPU_COUNT=0`, GPU override는 `1`로 설정하고 Spring을 재시작한다. 물리 GPU 번호는 `KURE_GPU_DEVICE_ID`이며 Spring count는 서버 모드와 일치하는 0/1이다. `/api/version`은 장치·정밀도·배치를 표시하고 요청 옵션 불일치는 400이다. 완전한 Ollama API는 제공하지 않는다.

## 검증

모델을 로드하지 않는 가상 추론 HTTP 동시성 검증:

```powershell
docker run --rm --entrypoint python -v "${PWD}/docker/kure:/tests:ro" -w /tests molelaw/kure-service:single-vector-cu128-v2 -m unittest -v test_server.py
```

CPU 이미지만 빌드했다면 이미지명을 `molelaw/kure-service:single-vector-cpu-v2`로 바꾼다. 테스트 컨테이너는 CPU 설정이며 GPU 할당·모델 다운로드가 필요 없다. 실제 추론과 처리 중 상태 조회는 실행한 Compose 조합으로 검증한다:

```powershell
Get-Content -Raw docker/kure/check_live.py | docker compose -f docker/kure/compose.yml -f docker/kure/compose.gpu.yml exec -T kure python -
```

임베딩 수/차원·유한 값·정규화와 상태 조회를 검사한다. 로그에는 입력 본문 없이 배치 건수·추론 시간을 기록한다. 모델 품질과 법령 목록·조문 두 단계 리랭커 검토는 [별도 환경 계획](../../docs/plans/alternate-environment.md)을 따른다.

## 선택적 조문 리랭커

`docker/kure/.env`에서 `KURE_RERANKER_ENABLED=true`를 설정하고 실행 명령에
`--env-file docker/kure/.env`를 지정하면 고정 BGE cross-encoder도 시작 시 로드한다.
KURE와 추론 잠금·대기열·캐시를 공유하며 CUDA에서는 리랭커만 FP16을 사용한다.
모델·revision·배치·토큰 한도는 `.env.example`을 따른다.
Spring의 `APP_LAW_RERANKER_ENABLED=true`와 모델/revision도 맞춘다.
상세 HTTP 계약·실패/길이 제한·예산은 [조문 리랭커](../../docs/law/article-reranking.md)를 따른다.

추가 모델을 다운로드하지 않는 회귀:

```powershell
docker run --rm --entrypoint python -v "${PWD}/docker/kure:/tests:ro" -w /tests molelaw/kure-service:single-vector-cu128-v2 -m unittest -v test_server.py test_reranker.py
```
