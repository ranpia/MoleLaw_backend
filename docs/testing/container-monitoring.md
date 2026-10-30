# 컨테이너 자원 대시보드

부하 시험 완료 후 cAdvisor v0.60.6을 연결했다. Grafana에서 **MoleLaw 부하 테스트 - 컨테이너 자원**을 연다.

| 패널 | 의미 |
| --- | --- |
| CPU 사용 코어 수 | 1코어는 Docker stats 약 100%. 백엔드 상한은 2코어 |
| CPU 상한 대비 사용률 | 해당 컨테이너의 CPU quota를 기준으로 나눈 값 |
| 메모리 워킹 셋·상한 비율 | JVM 힙이나 RSS와 같은 값은 아님 |
| 네트워크 수신·송신 | 컨테이너 인터페이스 합계. 요청 수와 다름 |
| 디스크 읽기·쓰기 | Docker Desktop이 제공하는 블록 장치 I/O |
| CFS 제한 기간 비율 | CPU 스케줄링 기간 중 quota 제한이 발생한 기간 비율 |

현재 테스트 서비스 9개에서 CPU·메모리·네트워크를 확인했고 디스크 I/O는 지원되는 5개 서비스에서 확인했다.
tmpfs·장치 I/O가 없는 컨테이너의 누락을 0으로 임의 대체하지 않는다. 종료한 Locust는 나타나지 않으며 다음 시험 때 자동으로 수집한다.
Qdrant/KURE도 같은 프로젝트의 rag 서비스로 실행하면 표시된다. 해당 서비스가 아직 실행되었다는 뜻은 아니다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile monitoring up -d --no-deps cadvisor
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml restart prometheus
```

Docker Desktop의 containerd 이미지 저장소에 맞춰 Docker/containerd 소켓과 namespace=moby를 사용한다.
Linux VM의 rootfs·sys·Docker 저장소를 읽기 전용으로 마운트한다. privileged 설정은 사용하지 않았다.
소켓은 read-only 마운트여도 Docker 제어 권한을 가질 수 있으므로 수집기 포트를 호스트에 공개하지 않는다.
container label은 compose project/service만 허용하고 환경변수 메타데이터는 수집하지 않는다.
Prometheus는 molelaw-loadtest 프로젝트 레이블만 보존한다. 수집기 자체는 Docker host 메타데이터를 볼 수 있다.

별도 장치 권한을 열지 않아 /dev/kmsg 기반 OOM 이벤트는 수집하지 않는다.
CPU·메모리·네트워크·블록 I/O 관측은 검증했지만 OOM 이벤트나 전체 호스트 디스크 분석까지 지원한다고 보지 않는다.

**이번 시험이 끝난 후 설치했기 때문에 이전 부하 시간대 그래프는 비어 있다.** 그때의 자원 표본은 로컬 docker-observations.jsonl에 있다.
다음 시험부터는 Prometheus의 3일 보존 기간 안에서 Grafana로 함께 비교할 수 있다.
수집기 추가에 따른 오버헤드도 다음 시험 조건에 기록한다.

공식 근거: [cAdvisor 실행](https://github.com/google/cadvisor/blob/master/docs/running.md),
[레이블·메트릭 옵션](https://github.com/google/cadvisor/blob/master/docs/runtime_options.md).
