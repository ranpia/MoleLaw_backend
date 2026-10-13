# CPU 임베딩 테스트 환경

기준 PC: i7-9700, RAM 32GB, UHD 630, 디스크 약 1TB 여유. GPU 장치를 전달하지 않는 CPU 실행 구성이다. 이미지 `molelaw/ollama-embedding:0.40.3-cpu`는 공식 Ollama `0.40.3`을 기반으로 빌드한다. 모델은 이미지에 넣지 않고 named volume에 다운로드한다.

## 자원 설정

| 항목 | 기본값 |
| --- | --- |
| CPU / 요청 스레드 | 컨테이너 최대 6 CPU / 스크립트 6 threads |
| 메모리 / 스왑 | 8GiB / 추가 스왑 없음 |
| 동시성 | 로드 모델 1개, 요청 1개, 대기열 8개 |
| 모델 유지 | 5분, 측정 후 명시적으로 언로드 |
| 호스트 주소 | `http://127.0.0.1:11435` |

Docker Desktop은 Linux containers/WSL2로 실행한다. Ollama 단독 테스트는 VM 메모리 10~12GiB, 기존 DB 세 서비스까지 함께 실행하면 18~20GiB를 시작 예산으로 제안한다. 호스트 IDE·JVM·브라우저 메모리와 실제 사용량에 따라 조정한다. CPU 상한 합계를 실제 코어 예약으로 해석하지 않으며 비교 시 다른 색인/빌드 작업을 멈춘다. 모델·이미지·캐시를 위해 우선 디스크 20GB 여유를 확보한다.

## 빌드와 실행

저장소 루트에서 실행한다. 기존 MySQL/Qdrant/Redis Compose와 독립된 프로젝트이며 DB 비밀값이 필요 없다.

```powershell
docker compose -f docker/ollama/compose.yml config --quiet
docker compose -f docker/ollama/compose.yml up -d --build --wait --wait-timeout 180
docker compose -f docker/ollama/compose.yml ps
```

처음에는 작은 모델 하나로 다운로드·임베딩을 확인한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docker/ollama/benchmark.ps1 -Models embeddinggemma -Pull
```

세 후보를 차례로 다운로드·측정한다. 최초 다운로드는 인터넷 연결이 필요하며 속도에 따라 오래 걸릴 수 있다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File docker/ollama/benchmark.ps1 -Pull
# 다운로드 후 반복 측정: 모델 태그가 바뀌지 않도록 -Pull 생략
powershell -NoProfile -ExecutionPolicy Bypass -File docker/ollama/benchmark.ps1 -Iterations 20
docker compose -f docker/ollama/compose.yml exec ollama ollama ps
docker compose -f docker/ollama/compose.yml stats --no-stream
```

모델은 `bge-m3`(기대 1024차원), `qwen3-embedding:0.6b`(1024), `embeddinggemma`(768)다. 모델별 입력 2개의 벡터 수·차원·유한값·0이 아닌 벡터를 검사한다. `truncate=false`로 입력 잘림을 허용하지 않고 CPU 실행 옵션을 요청에 지정한다.

결과는 Git에서 제외된 `docker/ollama/results/runtime-*.json`에 Ollama 버전·모델 digest·첫 요청 지연·warm median/p95·서버 시간·입력 토큰으로 저장한다. 다운로드 시간은 측정에 포함하지 않는다. 첫 요청 시간은 모델 언로드 후 측정이며 OS 파일 캐시까지 초기화한 완전한 cold boot는 아니다. 기본 5회 p95는 참고치이므로 비교에는 20회 이상을 권장한다.

**이 스크립트는 실행·지연 검사다.** 가상 조문과 질문을 raw 입력으로 사용하며 모델별 검색 instruction/prefix를 적용한 법률 검색 품질 비교가 아니다. 실제 법령 청크·모델별 입력 형식·Recall@K·RAM 측정·Qdrant 색인은 [RAG·ETL 계획](../../docs/plans/rag-etl.md)의 별도 평가로 진행한다. Spring 애플리케이션의 현재 OpenAI 임베딩 설정은 바꾸지 않는다.

## 중지·이동·문제 해결

```powershell
docker compose -f docker/ollama/compose.yml down
# 다른 장치에 빌드 이미지를 전달할 때 (모델 데이터는 포함되지 않음)
docker image save -o molelaw-ollama-cpu.tar molelaw/ollama-embedding:0.40.3-cpu
```

일반 `down`은 모델 볼륨을 보존한다. `down -v`는 다운로드한 모델을 삭제하므로 재다운로드를 의도할 때만 사용한다. 생성한 tar는 커밋하지 않는다.

11435 충돌 시 `$env:OLLAMA_LAB_PORT='11436'` 설정 후 기동하고 스크립트에 `-Port 11436`을 전달한다. OOM이면 입력 배치를 줄이고 메모리 사용량을 확인한다. 모델 누락은 `-Pull`, 엔진 연결 실패는 Docker Desktop 기동 상태를 확인한다. 실행 스크립트가 다른 로드 모델을 발견하면 측정을 중단하므로 전용 컨테이너에서만 실행한다.

## 구동 검증 이력

2026-10-13 현재 작업 환경에서 이미지 빌드·컨테이너 healthy, 6 CPU/8GiB/추가 스왑 없음 적용, EmbeddingGemma 다운로드·768차원 유효 벡터·반복 요청·모델 언로드·JSON 보고서 생성을 확인했다. BGE-M3와 Qwen3는 실행 대상에 포함했으나 이번 구동 검증에서는 다운로드/추론하지 않았다. 요청한 PC에서의 성능 수치나 법률 검색 품질을 검증한 결과는 아니다.

## 공식 참고

- [Ollama Docker CPU 실행](https://docs.ollama.com/docker)
- [임베딩 API와 truncate·keep_alive·실행 시간](https://docs.ollama.com/api/embed)
- [동시 모델·요청·대기열 설정](https://docs.ollama.com/faq)
- [Ollama 0.40.3 릴리스](https://github.com/ollama/ollama/releases/tag/v0.40.3)
