# Access stateless 구현

구현 기준: 2026-10-14. Redis Refresh 회전 구현을 기반으로 Access의 Redis 등록·조회·폐기 의존성을 제거했다. HTTP 세션 STATELESS와 별개로 토큰 활성 상태를 서버에 저장하지 않는다. 사용자 존재·현재 권한은 기존 MySQL 조회로 확인하므로 DB 의존성은 유지한다.

- 가입·로그인·OAuth·재발급은 동일한 Access 발급 경로를 사용한다. HS256 서명, tokenType=access, tokenVersion=3, iss=molelaw-auth, aud=molelaw-api, sub=양의 User.id 문자열, jti, iat, exp를 요구한다.
- 서명·허용 알고리즘·issuer/audience·용도·버전·필수 claim을 검증한다. 미래 iat, exp 이하가 아닌 iat, 만료된 토큰, 설정 TTL을 초과하는 수명은 거절한다. 시계 오차 허용은 0초다.
- jwt.access-ttl 기본값과 local/test는 5m이며 JWT 수명과 Access 쿠키 Max-Age는 300초다. 설정은 양의 정수 초, 최대 300초까지만 허용한다. 이전 local 15h 예외를 제거했다.
- 로그아웃은 제시된 Refresh family를 폐기하고 두 인증 쿠키를 삭제한다. 비밀번호 변경은 모든 Refresh family를 폐기한다. 두 작업 후에도 복사된 Access는 원래 만료까지 최대 5분 유효하다. 탈퇴 후에는 사용자 조회 실패로 기존 Access도 인증되지 않는다.
- Redis 장애·키 유실에도 유효한 기존 Access 인증은 계속된다. 로그인·가입·OAuth·재발급·Refresh 폐기는 Redis 성공을 요구하고 장애 시 503이다. 유실된 Refresh를 자동 복원하지 않는다. 유효한 Refresh 없는 로그아웃은 쿠키 삭제만 가능하다.
- Refresh r1 포맷·Redis namespace·회전·재사용 탐지·7일 절대 만료와 API JSON은 유지한다. Refresh는 HttpOnly 쿠키로만 전달한다. 가입 200 JSON, CSRF JSON→헤더 계약, 운영 Swagger 비활성화도 유지한다.

## 배포 전환

이전 버전 2 Access와 기존 15시간 Access는 새 검증에서 거절한다. 기존 정상 r1 Refresh는 유지하므로 /api/auth/refresh로 버전 3 Access를 받을 수 있다. 구형 JWT Refresh 또는 유실된 Refresh만 있으면 재로그인한다. 이전 버전 서버는 버전 3 Access를 거절하므로 혼합 버전 배포를 피하고 동일 릴리스로 전환한다.

기존 molelaw:auth:access:* 키는 더 이상 읽거나 쓰지 않는다. 이미 설정된 TTL까지 자연 만료하도록 두며 이 변경에서 Redis 데이터 삭제는 수행하지 않는다.

## 검증

일반 테스트 148개·독립 Redis 11개·MySQL 2개와 JAR 생성이 통과했다. 일반 테스트와 OpenAPI 생성 검증은 Redis 장애 중 Bearer/쿠키 Access 인증 성공, 로그인·회전·로그아웃 503, 가입 롤백, 로그아웃 후 Access 유지·Refresh 거절, 삭제 사용자 거절, 토큰 claim/알고리즘/수명/버전 거절, 프로필별 JWT·쿠키 수명 일치를 확인한다. 독립 Redis 회전 테스트와 MySQL 스키마 통합 테스트도 실행한다. 수동 브라우저·운영 배포 검증은 별도다.