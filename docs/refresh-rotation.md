# Redis Refresh 회전 구현

구현 기준: 2026-10-14. Access는 모든 프로필에서 5분 stateless JWT로 Redis를 조회하지 않는다. issuer/audience와 버전 3 검증을 적용했다. [Access 전환 계약](stateless-access.md)을 함께 참고한다.

## 토큰과 만료

- Refresh는 JWT 대신 `r1.<User.id>.<family UUID>.<256비트 난수>` 형식이다. 사용자 ID와 family는 조회용 선택자이며 권한 증명이 아니다. 클라이언트는 내용을 해석하지 않고 HttpOnly 쿠키로만 전송한다.
- Redis에는 전체 토큰의 SHA-256 해시만 저장한다. 난수는 SecureRandom 32바이트의 Base64URL 표현이며 로그·JSON 응답·Workbench 등에 원문을 기록하지 않는다. 내부 Grant의 toString도 토큰을 제외한다.
- family는 로그인/가입/OAuth 시점부터 절대 7일이다. 회전은 만료를 연장하지 않으며 Refresh 쿠키 Max-Age도 원래 만료까지 남은 시간으로 설정한다. Access 쿠키는 기존 `jwt.access-ttl`을 따른다.
- 구형 JWT Refresh·미등록·누락·잘못된 형식·만료·폐기 토큰은 401이다. 기존 JWT Refresh를 서명이나 DB 사용자 존재만으로 자동 등록하지 않는다. 전환 후 재로그인이 필요하다.

## Redis 키와 원자 연산

| 키 | 내용·만료 |
| --- | --- |
| `molelaw:auth:refresh:v1:{userId}:<familyId>` | HASH: current 해시, seen 해시들, expires, generation, revoked. family 절대 만료까지 유지 |
| `molelaw:auth:refresh:v1:{userId}:generation` | 사용자 세대 nonce. 가장 늦게 만료되는 family보다 먼저 만료되지 않도록 유지 |
| `molelaw:auth:refresh:v1:{userId}:families` | family ID와 절대 만료를 기록한 ZSET. 새 발급 시 만료 항목 정리 |

스크립트는 `src/main/resources/auth/refresh-*.lua`에 있다. 관련 키를 모두 KEYS로 전달하고 사용자별 동일 hash tag에 배치한다. 실제 통합 검증은 standalone Redis에서 수행했으며 Redis Cluster 전체 운영 검증은 별도다.

회전은 generation·family·만료·제시한 해시를 확인한 뒤 current/seen을 한 Lua 실행에서 갱신한다. 제시한 해시가 발급 이력에 없으면 401이며 family를 폐기하지 않는다. 공격자가 family 선택자만 알고 임의 secret을 보내 세션을 폐기할 수 없도록 한다.

이미 소비한 해시가 다시 제출되면 revoked를 설정하고 `REFRESH_TOKEN_REUSED` 401을 반환한다. 교체된 최신 Refresh도 이후 거절한다. 소비 이력과 폐기 tombstone은 family 절대 만료까지 남긴다. 동일 토큰의 동시 소비는 한 번만 성공하며 나머지 재사용 요청 때문에 성공한 응답의 새 Refresh도 폐기될 수 있다.

로그아웃은 현재 또는 이전에 발급했던 정상 Refresh 쿠키로 해당 family를 폐기한다. 회전과 경합해도 폐기 family는 되살아나지 않는다. 다른 로그인 family는 유지한다. 유효한 Refresh 쿠키가 없으면 서버가 특정 family를 식별·폐기할 수 없으며 클라이언트 쿠키만 정리한다. 발급된 Access는 만료까지 유효하다.

비밀번호 변경·탈퇴는 사용자 generation을 새 난수 nonce로 바꿔 모든 기존 family를 거절한다. 세대 키 유실 뒤 새 로그인도 새 nonce를 사용하므로 남아 있던 예전 family가 자동 부활하지 않는다. family 목록 조회 API·최대 활성 세션 제한·HTTP 호출 제한은 후속 범위다.

## 계정 작업과 실패 처리

일반 로그인, OAuth 발급, 재발급, 비밀번호 변경과 탈퇴는 사용자 행의 pessimistic write lock으로 계정 변경과 발급을 직렬화한다. 저장소 구현은 `auth.security`, 계정 잠금 쿼리는 `user.repository`에 있다. 가입은 저장 후 생성된 사용자 PK를 사용한다.

MySQL과 Redis는 단일 트랜잭션이 아니다. Redis 폐기를 먼저 확인하고 비밀번호·탈퇴 DB 변경을 수행한다. Redis 폐기 실패 시 503이며 MySQL 변경은 롤백한다. Redis 폐기 후 DB commit이 실패하면 Refresh 폐기는 되돌리지 않고 재로그인을 요구한다. 실패한 작업을 성공으로 응답하지 않는다.

발급·회전의 저장소 처리 완료 전에는 인증 쿠키·성공 JSON을 전달하지 않는다. Refresh 상태 전이 후 다음 단계 실패 또는 응답 유실은 자동 보상으로 이전 Refresh를 다시 활성화하지 않는다. 회전 후 다음 단계 실패/응답 유실로 새 토큰을 받지 못했다면 재로그인한다. 원문을 받지 못한 발급의 잔여 상태는 TTL로 정리한다.

Redis 오류·연결 실패·noeviction 메모리 부족은 503 `AUTH_STORE_UNAVAILABLE`이다. 인프로세스 저장소나 JWT 서명으로 우회하지 않는다. 기존 Access 인증은 Redis를 호출하지 않아 유지되지만 로그인·회전·폐기는 실패한다. 사용자 DB·권한·CSRF 검사는 별개다.

Lua는 다른 요청과의 실행 격리를 제공하지만 임의 실행 오류에 대한 자동 롤백을 제공하지 않는다. 오류 시 성공 응답을 반환하지 않으며 임의로 소비 상태를 되돌리지 않는다. 테스트 전용 대체 저장소는 `src/test`에만 있다.

## 프론트 API 계약

CSRF·Origin·credentials 계약은 유지한다. 먼저 `GET /api/auth/csrf`를 완료하고 JSON token을 `X-XSRF-TOKEN`에 넣으며 모든 상태 변경 요청에 쿠키를 함께 전송한다.

| API | 성공 동작 |
| --- | --- |
| `POST /api/auth/signup` | 기존 200 성공 문자열 JSON과 Access/Refresh HttpOnly 쿠키 |
| `POST /api/auth/login` | `{"accessToken":"<access-jwt>"}`와 두 HttpOnly 쿠키 |
| OAuth 성공 콜백 | 두 HttpOnly 쿠키 발급 후 프론트 리다이렉트 |
| `POST /api/auth/reissue` | `{"success":true,"data":{"accessToken":"<new-access-jwt>"}}`와 Access/회전한 Refresh 쿠키 |
| `POST /api/auth/logout` | 제시된 Refresh family 폐기, 쿠키 삭제, 기존 성공 JSON |
| 비밀번호 변경·탈퇴 | 모든 Refresh family 폐기 후 기존 성공 상태/본문 계약 |

Refresh는 API DTO·OpenAPI schema·JSON 예시에서 제거했다. Path는 `/api/auth`, HttpOnly는 모든 환경에서 유지하고 발급/삭제 시 이전 Path=/ 쿠키도 정리한다. 운영 Secure/SameSite와 local/test 예외는 기존 설정을 유지한다.

재발급 오류는 기존 400 TOKEN_FAIL 대신 401 `INVALID_REFRESH_TOKEN` 또는 `REFRESH_TOKEN_REUSED`의 공통 ApiResponse 오류 JSON이다. Redis 오류는 503이다. OAuth 콜백의 저장소 오류는 503 `{"error":"AUTH_STORE_UNAVAILABLE"}`, 발급 전 세대 충돌은 401 `{"error":"INVALID_REFRESH_TOKEN"}`이다.

프론트는 탭 간에도 재발급 요청을 하나로 합치고 사용한 Refresh로 자동 재시도하지 않는다. 401은 재로그인으로 처리하며, 503/응답 유실 뒤에도 회전 전 토큰 재시도로 성공을 가정하지 않는다. 현재 단계에서 재사용 탐지·전체 Refresh 폐기는 이미 발급된 모든 Access의 즉시 폐기를 뜻하지 않는다. 로그아웃도 Access를 즉시 폐기하지 않으며 기존 Access는 모든 프로필에서 만료까지 최대 5분 유효하다.

## 검증과 운영

```powershell
.\gradlew.bat test bootJar
# Docker의 독립 Redis 7.4.11 컨테이너. 기존 개발 Redis는 변경하지 않음.
.\gradlew.bat redisIntegrationTest
.\gradlew.bat mysqlIntegrationTest
```

2026-10-14 일반 테스트 148개, 실제 Redis 테스트 11개, 실제 MySQL 테스트 2개와 JAR 생성이 통과했다. 실제 Redis 검증은 절대 TTL, 단일 소비·재사용, 로그아웃/회전 경합, 사용자 세대·키 유실, 임의 secret에 의한 세션 폐기 거절, 연결 실패·메모리 부족·Redis 명령 오류를 포함한다. API 검증은 JSON 제거·쿠키 갱신·폐기·계정 변경 롤백·구형 토큰 거절·CSRF/OpenAPI 회귀를 포함한다. 수동 브라우저·운영 배포 검증은 별도다.

로컬 Redis는 영속화 없이 실행하므로 재시작 시 Refresh가 유실되고 재로그인이 필요하다. 운영은 전용 용량·noeviction·인증/TLS·영속화·복제·복구를 별도 준비한다. 오래된 Redis snapshot 복원이나 MySQL ID 초기화는 기존 세션과 사용자 매핑을 되살릴 수 있으므로 신뢰할 수 없는 상태는 인증 namespace와 키 전환으로 무효화한다. MySQL 복원만으로 Refresh를 복구하지 않는다.

모니터링은 Redis Insight의 `molelaw:auth:refresh:v1:*` 패턴으로 hash·절대 만료·revoked·generation을 확인할 수 있다. 토큰 원문을 콘솔이나 Workbench에 넣지 않는다. Lua와 클라이언트 동작 근거는 [Redis 스크립팅](https://redis.io/docs/latest/develop/programmability/eval-intro/)과 [Spring Data Redis 스크립팅](https://docs.spring.io/spring-data/redis/reference/redis/scripting.html)을 따른다.
