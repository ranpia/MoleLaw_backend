# MySQL·Redis 관측

부하 시험용 DB만 수집한다. 개발 DB·개발 Redis는 중지 상태다.

## 보는 곳

Grafana의 **MoleLaw 부하 테스트 - MySQL·Redis** 대시보드다. 백엔드 대시보드의 Hikari는 연결 풀이고 이 화면은 DB 자체 상태다.

| 대상 | 지표 | 해석 |
| --- | --- | --- |
| MySQL | mysql_up, 연결·실행 스레드 | 연결 가능 여부와 활성 작업 수 |
| MySQL | 초당 쿼리 수 | API 외에도 health·exporter·연결 풀 점검 포함 |
| MySQL | 행 잠금 대기 | 읽기 시험에서 0이어도 쓰기 충돌 성능을 증명하지 않음 |
| InnoDB | 버퍼 풀 data/free, 물리 읽기 | 반복 조회에서 데이터가 메모리에 머무는 정도 |
| Redis | 연결·메모리·초당 명령 | 로그인·Refresh 상태 저장소의 자원 사용 |
| Redis | 거부 연결·키 퇴출·만료 | 연결 한계·메모리 압박·TTL 동작 |

시험 당시 MySQL과 Redis의 CPU·컨테이너 메모리·디스크 I/O는 Docker stats로 별도 관측했다.
시험 뒤 [컨테이너 대시보드](container-monitoring.md)를 연결해 지금부터는 Grafana에서도 볼 수 있다.
쿼리 지연 분포·개별 SQL 분석·느린 쿼리 로그도 이번 대시보드 범위 밖이다. 단순 QPS를 SQL 효율이나 사용자 처리량과 같게 보지 않는다.

## 준비와 재기동

최소 환경을 먼저 시작한 뒤 저장소 루트에서 실행한다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/load-testing/prepare-mysql-monitoring.ps1
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile monitoring up -d --no-deps redis-exporter
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml restart prometheus
```

MySQL 준비 스크립트는 `molelaw-loadtest` 컨테이너인지 확인한 뒤 전용 exporter 계정을 만든다.
비밀번호는 Git 제외 `.env`의 `LT_MYSQL_EXPORTER_PASSWORD`에 무작위 hex로 저장하고 출력하지 않는다.
모니터링 사용자에게 PROCESS·REPLICATION CLIENT·SELECT 권한만 주고 동시 연결을 3개로 제한한다. 데이터 쓰기·DDL 권한은 없다.
이 권한은 테스트 MySQL 인스턴스 전역에 적용되며 운영 DB 계정을 만들거나 연결하지 않는다.
Redis exporter는 격리 환경의 Redis 인증값을 사용한다. 키 이름·값을 수집하는 옵션은 활성화하지 않는다.
exporter 포트는 호스트에 공개하지 않고 Prometheus가 내부에서 5초마다 수집한다.

## UNKNOWN 요청

`UNKNOWN`은 HTTP 지표의 URI 매칭 정보가 없는 경우의 값이다. 컨트롤러 이전의 보안 차단 등이 해당한다.
이번 환경에서는 GET·401 계열을 확인했다. 사전 점검의 익명 방 조회·일반 포트의 지표 접근 차단·Swagger 접근 차단 등이 포함될 수 있다.
URI가 없는 집계이므로 각 요청의 원래 URL을 이 지표만으로 특정할 수 없다.
실제 Prometheus 수집은 내부 `backend:9091/actuator/prometheus`이며 UP으로 별도 확인한다.
대시보드에는 ‘경로 미확정(보안 차단 등)’으로 설명하되 오류 데이터는 숨기지 않는다.

참고: [MySQL exporter](https://github.com/prometheus/mysqld_exporter), [Redis exporter](https://github.com/oliver006/redis_exporter).
