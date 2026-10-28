# 로컬 환경 조사와 준비

조사일: 2026-10-28. 브랜치: `docs/load-test-plan`. 실행 중 서비스는 조사 당시의 개발 환경이며 부하 전용 환경이 아니다.
서버 재시작·설정 변경·색인·유료 호출·부하 발생은 수행하지 않았다.

## 확인한 환경

| 구분 | 확인 결과 |
| --- | --- |
| 호스트 | Windows, i7-9700, 논리 CPU 8개, RAM 약 31.9GiB |
| GPU | Intel UHD Graphics 630 확인. 현재 추론 컨테이너 CUDA 사용 불가 |
| Docker Engine | 29.8.2, CPU 8개·메모리 약 15.6GiB 할당 |
| 호스트 Java | JDK 17.0.12, `C:\gen-ai-course\tools\jdk-17` |
| 백엔드 Java | Temurin 17.0.20.1 |
| 호스트 Python·Locust | PATH에서 실제 Python 설치를 확인하지 못함. python.exe는 WindowsApps 별칭이며 Locust 명령 없음 |
| 실행 방법 | 공식 `locustio/locust:2.47.0` Docker 이미지로 호스트 Python 설치 없이 준비 |
| 도구 확인 | `--version` 실행 성공: Locust 2.47.0, Python 3.14.8. 이미지 digest `sha256:90fdf5b9632e67bb556d17784095652871be68318c5074731d911d4caaa42cc3` |
| 직전 코드 검증 | JDK 17 `test --rerun-tasks`: 544개, 실패·건너뜀 0 |

| 서비스 | 호스트 포트 | CPU 상한 | 메모리 상한 | 조사 결과 |
| --- | --- | --- | --- | --- |
| 백엔드 | 8080 | 2 | 2GiB | healthy, local 프로필 |
| MySQL 8.4.11 | 3307 | 2 | 2GiB | healthy |
| Redis 7.4.11 | 6379 | 1 | 256MiB | healthy |
| Qdrant 1.19.1 | 6333·6334 | 4 | 6GiB | running. Docker health 표시만으로 검색 정상 여부는 미확정 |
| KURE | 11437 | 6 | 8GiB | healthy, 임베딩 모델 로드 확인 |
| Redis Insight | 5540 | 별도 | 512MiB | running, 부하 대상 아님 |

주요 서비스의 CPU 상한 합은 15개, 메모리 상한 합은 18.25GiB다. Redis Insight까지 포함하면 18.75GiB다.
상한은 예약량이 아니지만 Docker VM 용량보다 커서 동시 부하 시 경쟁할 수 있다.
초기 관측에서 KURE 약 1.06GiB·Qdrant 약 1.63GiB였으며 이는 유휴 근처의 한 시점 값이고 최대 사용량이 아니다.
현재 PC에서 Locust까지 실행하면 발생기와 측정 대상이 자원을 공유하므로 운영 수용량으로 일반화하지 않는다.

## RAG 실행 설정

- KURE: `nlpai-lab/KURE-v1`, revision `8b418a58414668e75532ed045c22d9ca018ae2b2`, CPU float32, batch 16, 스레드 6개.
- 실행 런타임: Python 3.12.15, torch 2.7.1+cpu, sentence-transformers 6.1.0, transformers 5.0.0.
- 임베딩·리랭커 공유 gate: 실행 1개 + 대기 최대 4개, 잠금 대기 제한 5초. 초과·대기 만료는 503 경로다.
- 리랭커 활성화: `BAAI/bge-reranker-v2-m3`, batch 2, 입력 쌍 최대 1024토큰. 모델 서버와 백엔드 모두 enabled 확인.
- 현재 로그는 임베딩 실행 시간을 일부 기록하지만 대기열 깊이·잠금 대기·리랭크 전체 계측은 추가 조사·구현이 필요하다.
- `/api/version`, `/api/ps`는 각각 1회 조회했다. 실제 임베딩·리랭킹을 요청하지 않았다.

## 연결과 미확인 사항

백엔드 `/api/auth/csrf`는 1회 요청에 HTTP 200을 반환했다. 토큰·쿠키는 출력하거나 문서에 저장하지 않았다.
`/actuator/prometheus`는 인증 없이 401을 반환했다. 이는 endpoint 존재 여부를 증명하지 않는다.
현재 소스에는 Actuator·Prometheus registry 의존성이 없어 지표 수집 환경을 별도로 구현해야 한다.
MySQL Hikari의 소스 기본 풀 크기는 10이며 실행 중 실효값은 아직 확인하지 않았다.

백엔드 이미지는 2026-10-27에 생성됐다. 현재 소스의 2026-10-28 테스트 통과가 실행 컨테이너의 같은 코드 실행을 증명하지 않는다.
성능 측정 전 빌드 커밋·이미지 digest를 연결하고 테스트 전용 백엔드를 새로 준비해야 한다.

아직 준비되지 않은 항목:

- 부하 전용 MySQL DB·Redis 키/인스턴스·Qdrant 컬렉션, 테스트 사용자와 데이터 건수 확인.
- 로그인 쿠키·CSRF를 안전하게 전달하는 방법과 사용자별 독립 방 데이터.
- 질문 세트·검색 근거 품질 기준·실행할 API 시나리오.
- Prometheus/Grafana·exporter·RAG 단계 추적·중단 장치.
- 실제 MySQL 설정·슬로 쿼리 수집 여부, Qdrant 컬렉션 크기와 실제 검색 확인.

기존 named volume과 모델 캐시가 연결돼 있으므로 새 테스트 환경에서 기존 DB 볼륨을 재사용하지 않는다.
모델 캐시 재사용과 추론 서비스 공유는 다르다. 추론 서비스를 공유하면 개발 요청과 부하 요청이 같은 대기열에 들어간다.

## 다시 확인하는 방법

저장소 루트 PowerShell에서 아래를 실행한다. 스크립트는 컨테이너 상태·자원·Locust 이미지 존재만 읽고 서비스에는 요청하지 않는다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/load-testing/preflight.ps1
```

Locust 도구만 확인하는 명령이다. 첫 실행은 이미지를 내려받고, 버전 출력 후 임시 컨테이너를 제거한다. 트래픽은 발생시키지 않는다.

```powershell
docker run --rm locustio/locust:2.47.0 --version
```

공식 근거: [Locust Docker 실행 안내](https://docs.locust.io/en/stable/running-in-docker.html).
실제 HTTP 시나리오 파일과 Web UI는 아직 준비하지 않았다. 도구가 실행된다고 부하 준비가 완료된 것은 아니다.

## 다음 실습

첫 실습은 전용 데이터와 백엔드의 준비부터 진행한다. 기존 개발 서비스의 한계 측정은 하지 않는다.
계측 추가 후에는 외부 AI를 호출하지 않는 조회 API를 사용자 1명으로 실행해 응답·지표를 함께 확인한다.
진행 상태는 [LT01~LT08 계획](../plans/load-testing.md)에서 관리한다.
