# KURE CPU 서비스

기존 단일 HTTP 서버를 대체하는 어댑터다. HTTP 상태 조회는 병렬 처리하고, CPU 추론·모델 해제는 하나의 잠금으로 직렬화한다. 기본 대기열은 4개이며 대기 시간이 5초를 넘거나 대기열이 가득 차면 503을 반환한다. CPU 추론 속도 자체가 빨라진다는 의미는 아니다.

## 실행

기존에 검증한 로컬 런타임 이미지 `molelaw/kure-embedding:st6.1.0-cpu`와 모델 볼륨 `molelaw-kure-lab_kure_models`를 재사용한다. 베이스 이미지가 없는 새 장치에서는 해당 런타임을 먼저 준비해야 한다. Dockerfile의 `KURE_RUNTIME_IMAGE`와 Compose의 `KURE_MODEL_VOLUME`으로 대상을 변경할 수 있다. `evaluation/` 실험 파일은 수정하지 않는다.

```powershell
docker compose -f docker/kure/compose.yml up -d --build
```

기본 호스트 포트는 `11437`이다. 루트 `.env`의 `APP_LAW_VECTOR_EMBEDDING_BASE_URL=http://localhost:11437`로 연결하고 Spring 서버를 재시작한다. 기존 11436 실험 서버는 자동으로 종료하지 않는다. 모델·revision·1024차원·float32·CPU 6스레드 설정과 `/api/embed` 계약을 유지한다. 완전한 Ollama API는 제공하지 않는다.

## 검증

모델을 로드하지 않는 가상 추론 HTTP 동시성 검증:

```powershell
docker run --rm --entrypoint python -v "${PWD}/docker/kure:/tests:ro" -w /tests molelaw/kure-service:threaded-v1 -m unittest -v test_server.py
```

실제 임베딩과 처리 중 상태 조회 검증(16개 입력을 실제 CPU로 추론):

```powershell
docker run --rm --network container:molelaw-kure-service-kure-1 --entrypoint python -v "${PWD}/docker/kure:/checks:ro" molelaw/kure-service:threaded-v1 /checks/check_live.py
```

2026-10-22 실행에서 16개/1024차원 임베딩 약 10.2초, 동시 상태 조회 49회, 최대 응답 0.01초를 관측했다. 일반적인 부하 성능이나 다중 사용자 처리량을 보장하는 수치는 아니다. 로그에는 입력 본문 없이 배치 건수·추론 시간을 기록한다.
