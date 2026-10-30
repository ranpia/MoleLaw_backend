# Redis 시험 중지 지점

이 문서는 10월 28일 중지 시점의 기록이다. 10월 29일 재개 후 [Redis·Refresh API 검증](redis-performance.md)을 완료했다.

2026-10-28 사용자의 요청으로 부하 발생기를 종료한 뒤, 추가 요청으로 `molelaw-loadtest` 프로젝트 컨테이너 11개도 모두 중지했다.
실행 중인 테스트 컨테이너는 없다. 컨테이너·데이터 볼륨·측정 파일은 삭제하지 않았다.
Redis는 비영속·tmpfs 설정이므로 재시작 후 테스트 로그인 상태와 합성 키를 다시 준비한다.
전체 시험 완료가 아니며, `result.json`은 생성되지 않았다. 변경 파일은 현재 작업 브랜치의 미커밋 상태로 보존한다.

## 완료한 측정

원본: Git 제외 `build/redis-loadtest-runs/redis-20261028T082541Z/`.
Redis 7.4.11, CPU 0.5개, 컨테이너 메모리 256MiB, maxmemory 128MiB, noeviction, 비영속 설정이다.
기존 인증 Redis와 별도인 `redis-bench`를 사용했다.

| SET, 값 1KiB, pipeline 1 | OPS | p95 ms | 측정 초 |
| --- | ---: | ---: | ---: |
| 연결 1 | 11,360.25 | 0.095 | 67.5 |
| 연결 10 | 42,141.54 | 0.127 | 80.5 |
| 연결 50 | 41,663.43 | 0.527 | 99.0 |
| 연결 100 | 38,119.48 | 1.791 | 78.5 |
| 연결 200 | 40,053.42 | 13.399 | 96.5 |

애플리케이션 원본 Lua의 정상 회전·절대 만료 유지·재사용 후 family 폐기·만료 거부·세대 키 유실 거부를 확인했다.
같은 토큰 32개 동시 요청은 회전 성공 1·재사용 탐지 1·폐기 거부 30이었다. 먼저 발급된 토큰도 이후 거부됐다.
이 결과는 Redis 직접 Lua 검사이며 실제 HTTP API 검증 결과가 아니다.

## 남은 작업과 재개 주의점

- PING은 보정 측정 후 본 측정 중 중지됐다. 완료 결과로 사용하지 않는다.
- GET·SETEX 지속 측정, 값 크기 64B/4KiB와 pipeline 10/100 비교는 미완료다.
- Lua 지속 부하 1/10/50연결, Redis OOM·TTL 회복은 미실행이다.
- 실제 Refresh API의 정상/동시 부하·오류·장애·복구는 미실행이다. `refresh_suite.py`와 `run-refresh-capacity.ps1`은 준비 중이며 문법 컴파일만 확인했다.
- 부하 스크립트는 전체 실행용이고 현재 키가 있으면 거부한다. 재개 전에 완료된 SET 측정을 재사용하도록 단계 선택·재개 기능을 추가한다. 기존 원본은 덮어쓰지 않는다.
- Lua 부하의 합성 current/next 값은 현재 짧은 문자열이다. 실제 SHA-256 해시와 같은 64자 길이로 바꾼 뒤 지속 측정한다. 기능 검사의 짧은 값은 별도로 구분한다.
- 네이티브 redis-benchmark는 REDISCLI_AUTH를 지원하지 않았다. 전용 인스턴스에만 `loadbench` named ACL user를 nopass로 만들고 bench:*의 GET/SET/SETEX, PING/SELECT, 도구 초기화를 위한 CONFIG GET 및 CLIENT SETNAME/SETINFO만 허용했다. default 사용자의 비밀번호는 유지한다. 호스트 포트는 공개하지 않았다. API 시험 전 benchmark 계정을 비활성화하는 절차도 보완한다.
- 초기 기능 검사 실패는 합성 family 이름이 generation 키와 겹친 테스트 데이터 문제였고 수정했다. 앞선 실패 실행 082302Z·082341Z·082412Z는 성공 결과에 합치지 않는다.
- Prometheus에 벤치 Redis job을 추가했고 DB 대시보드의 Redis 범례에 job을 표시했다. 파일에는 후속 refresh-backend scrape도 추가됐으나 API 환경은 아직 실행하지 않았다.
- Toxiproxy 2.12.0, 별도 refresh-backend/refresh-load의 Compose 설정을 준비했지만 시작하지 않았다. 기존 백엔드의 Redis 연결은 변경하지 않았다.
- API 시험 시 프록시를 경유한 응답 유실 요청은 호스트가 달라 Cookie를 명시적으로 전달하도록 작성했다. 실제 동작 검증이 필요하다.

재개 순서는 미완료 Redis 측정 → 직접 Lua 부하·OOM·복구 → 별도 백엔드 시작 → Refresh API 기능·부하·장애 검증이다.
Redis 시험을 진행하는 동안 API 시험을 동시에 실행하지 않는다. 비밀값과 원본 결과는 Git에 넣지 않는다.

프록시 기능은 [Toxiproxy 공식 문서](https://github.com/Shopify/toxiproxy)를 기준으로 준비했다.
