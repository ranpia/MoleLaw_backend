# 인증·보안 실행 계획

상태: 1단계 즉시 개선 구현 (2026-10-13), 자동 테스트 검증 완료. Redis Compose 검증은 완료했지만 Spring Redis 연동·Refresh 테이블·회전·폐기는 미구현이다. 전체 순서는 [재구성 요약](../backend-rebuild-plan.md), 현재 코드의 문제·목표 구조는 [JWT 상세 검토](../jwt-security-review.md)를 따른다. 인증 정책 결정과 저장소 전환을 이 문서에서 추적한다.

## 범위와 확정 사항

- Access JWT는 Redis의 `jti`·로그인 세션 활성 상태를 매 요청 검증한다. 키 누락·장애 시 JWT만으로 인증하지 않는다.
- Refresh는 MySQL 별도 테이블에서 해시·만료·회전 관계·폐기를 관리한다. 브라우저에는 HttpOnly 쿠키로만 전달한다.
- 로그인 세션 ID는 Advisor의 상담방 ID와 별개다. 로그아웃으로 상담 기록을 지우지 않는다.
- 일반 로그인·가입·OAuth·재발급·로그아웃이 동일한 발급·쿠키·폐기 서비스를 사용한다.

## 구현 순서

1. **즉시 개선:** `JwtUtil`, `JwtAuthenticationFilter`, `OAuth2SuccessHandler`의 토큰·payload 로그 제거. Access/Refresh 용도 분리, Origin 허용 목록·CSRF 계약을 적용한다.
2. **API 계약:** Refresh 응답 필드 제거, 만료 Access 없이 Refresh로 재발급, 쿠키 발급·삭제 속성 통일. 프론트 재발급·로그아웃 흐름과 함께 변경한다.
3. **MySQL 저장소:** 명시적 마이그레이션으로 세션·Refresh 해시·family·교체 관계·만료·폐기 상태를 추가한다. 해시 유일성, 조회 인덱스, 동시 회전용 잠금 또는 조건부 갱신을 정의한다.
4. **Redis 인증:** 클라이언트·프로필·TTL·활성 세션·사용자별 세션 인덱스를 구현한다. JWT 서명·용도·사용자·세션 검증과 연결한다.
5. **회전·폐기:** 회전 재사용 탐지, 로그아웃·탈퇴·비밀번호 변경의 폐기 범위와 저장소 부분 실패 복구를 구현한다.

## 구현 전 결정할 정책

| 항목 | 초기 제안·결정 조건 |
| --- | --- |
| 수명·형식 | Access 15분, Refresh 7일·고엔트로피 난수 제안. 절대 만료와 연장 한도를 결정한다. |
| Access 전달 | 쿠키 또는 메모리+Authorization 중 프론트 계약으로 선택. 양쪽 허용 시 충돌 처리도 정의한다. |
| 쿠키·CSRF | 운영 사이트 관계에 맞춘 SameSite, Secure, Path와 CSRF 토큰·Origin 검사. 로컬 HTTP 예외는 local에 한정한다. |
| 다중 탭·응답 유실 | 프론트 재발급 요청을 합친다. 서버는 한 Refresh의 한 번 소비를 보장한다. 초기에는 소비 후 응답 유실 시 재로그인을 요구하는 단순 정책을 비교하며, 유예 처리는 탈취 토큰 재사용 위험과 함께 별도 결정한다. |
| 세션·보관 | 기기별/전체 로그아웃, 최대 활성 세션, 폐기·회전 이력 보관 기간과 정리 작업을 정의한다. 재사용 탐지에 필요한 이력은 즉시 삭제하지 않는다. |
| 키·기존 토큰 | 서명 알고리즘 허용 목록·키 교체 절차·시간 오차 허용치를 정의한다. 새 저장소에 없는 기존 토큰은 전환 시 재로그인을 요구하며 JWT만으로 자동 등록하지 않는다. |

## 장애·동시성 보완

- MySQL과 Redis에는 단일 트랜잭션이 없다. 발급·회전·폐기 각각의 상태 전이, 기록 순서, 보상 작업과 재시도 식별자를 설계하고 나서 구현한다. Redis 등록 및 MySQL 상태 확정 전에는 새 토큰을 전달하지 않는다.
- Redis 폐기 실패를 단순 재시도만으로 처리하면 이미 등록된 Access가 남는다. 세션 폐기와 재발급이 경합해 세션을 되살리지 않도록 버전 또는 폐기 시각을 사용하고, 폐기 미반영 동안의 인증 차단 장치를 검증한다. 차단을 보장하지 못하면 해당 설계는 배포하지 않는다.
- Redis 비영속 재시작 후에는 정상 MySQL Refresh로만 복구한다. 복구 직전 세션 상태를 다시 확인하며 폐기된 세션은 복원하지 않는다.
- 인증 실패는 401, 권한 부족은 403, 저장소 장애는 서비스 오류로 구분한다. 프론트가 장애를 토큰 만료로 오인해 무한 재발급하지 않도록 재시도 상한·오류 코드를 맞춘다.
- 원문 토큰 없는 보안 이벤트와 회전 충돌·재사용 탐지·폐기 지연·Redis 오류 지표를 기록한다. 로그인·재발급 호출 제한과 민감 작업 재인증을 포함한다.

## 완료 조건

- [ ] 교차 토큰 사용·잘못된 서명/용도/issuer/audience·만료·삭제 사용자를 거절한다.
- [ ] 만료 Access로 재발급할 수 있고 동시 Refresh 소비는 한 번만 성공한다. 재사용 시 해당 family를 폐기한다.
- [ ] 로그아웃·탈퇴·비밀번호 변경 후 폐기 범위의 Access·Refresh가 차단된다. 회전/폐기 경합과 두 저장소 부분 실패를 재현한다.
- [ ] Redis 키 유실·장애·재시작에서 인증 우회·자동 부활이 없다. 실제 Redis TTL·폐기 및 실제 MySQL 잠금 검증을 별도로 수행한다.
- [ ] 쿠키·CORS·CSRF와 일반 로그인/OAuth를 실제 브라우저에서 검증한다. 외부 저장소 없는 단위 테스트도 유지한다.
- [ ] API 변경·전환 재로그인·키 교체·복구 절차를 문서화한다. 상담방 소유권 검사는 인증과 별도로 유지한다.

## 1단계 구현과 프론트 전환 계약 (2026-10-13)

- 회원가입 성공은 `200`과 `{"success":true,"data":"회원가입 되었습니다."}` 및 JWT 쿠키를 반환한다. 기존 백엔드 `/Main` 리다이렉트는 제거했으며 프론트가 성공 응답을 받은 뒤 화면을 이동한다. 회원가입은 인증 없이 접근 가능하지만 CSRF 토큰과 Origin 정책은 적용한다.

- JWT 원문·payload·파싱 예외 로그를 제거했다. `tokenType=access/refresh`와 HS256 서명, 만료, 필수 claim을 검증하며 교차 사용과 용도 없는 기존 토큰은 거절한다. 배포 후 기존 사용자는 다시 로그인해야 한다.
- CORS는 `security.allowed-origins` (`CORS_ALLOWED_ORIGINS`, 쉼표로 구분한 명시적 Origin)만 허용한다. 생략하면 단일 `FRONTENDURI`를 사용한다. OAuth 리다이렉트용 `FRONTENDURI`에는 목록을 넣지 않는다.
- 프론트는 먼저 `GET /api/auth/csrf`를 `credentials: "include"`로 호출한다. 응답 `{token, headerName}`의 토큰을 메모리에 두고 모든 POST/PUT/PATCH/DELETE 요청에 해당 헤더(기본 `X-XSRF-TOKEN`)와 credentials를 보낸다. 로그인·가입·재발급·로그아웃에도 필요하다. 토큰을 담은 `XSRF-TOKEN` 쿠키는 HttpOnly이며 JS에서 직접 읽지 않는다.
- 상태 변경 요청의 Origin은 같은 서버 주소이거나 외부 Origin 허용 목록과 일치해야 한다. Origin이 없는 클라이언트도 CSRF 토큰을 제출해야 한다. 누락·잘못된 CSRF 또는 허용되지 않은 Origin은 403이다. Bearer 호출도 동일한 CSRF 계약을 따른다.
- Access는 Authorization Bearer를 우선하고 없을 때 accessToken 쿠키를 사용한다. Refresh로 일반 API를 인증하거나 Access로 재발급할 수 없다. `/api/auth/reissue`는 Access 인증 없이 Refresh 자체와 사용자 존재를 검증한다.
- 일반 로그인·가입·OAuth·재발급·탈퇴·로그아웃은 공통 쿠키 설정을 사용한다. 운영 기본은 Secure=true, SameSite=None, HttpOnly=true이며 `COOKIE_SAME_SITE=Lax/Strict`로 같은 사이트 정책을 선택할 수 있다. Secure=false는 local/test만 허용하고 None과 함께 사용할 수 없다. local/test는 Lax다.
- Access Path=/, Refresh Path=/api/auth로 통일했다. Refresh 발급·삭제 시 이전 Path=/ 쿠키도 삭제한다. 쿠키 수명은 현재 JWT와 같은 Access 15시간, Refresh 7일이다. Access 15분 변경은 저장소 전환 단계에 남겨 둔다.
- Refresh JSON 응답 제거, 서버 저장소·회전·폐기와 issuer/audience 검증은 후속 단계다. 현재 로그아웃은 쿠키만 제거하므로 복사한 JWT의 서버 폐기를 제공하지 않는다.

자동 테스트와 별도로 배포 전 실제 브라우저에서 로컬 HTTP 및 운영 HTTPS의 가입·로그인·OAuth 리다이렉트·재발급·로그아웃, 쿠키 전달과 삭제, CSRF 403 후 재조회 흐름을 확인해야 한다. 프론트의 CSRF 헤더 적용과 함께 배포한다.
검증:  .\gradlew.bat test  전체 성공. JWT·인증 필터·재발급·쿠키·CORS·CSRF 회귀 테스트 포함. 실제 브라우저 검증은 미수행.

Swagger 같은 Origin 요청도 CSRF 토큰을 검증한다. 외부 Origin은 명시적 허용 목록으로 제한한다. 내부 ERROR 디스패치는 인증 없이 처리해 CSRF/Origin 거절의 403이 401로 덮어써지지 않도록 한다. 직접 /error 접근과 일반 보호 API의 인증 요구는 유지한다.

Swagger UI는 GET /api/auth/csrf 응답의 token을 Authorize → CsrfToken에 한 번 등록하면 상태 변경 요청에 X-XSRF-TOKEN 헤더를 자동으로 전송한다. CSRF 쿠키가 유지되어야 하며 토큰 재발급 후에는 등록 값도 갱신한다. GET 요청에는 CSRF 보안 요구를 적용하지 않는다.

Swagger Authorize 항목은 AccessCookie, CsrfToken으로 구성하며 Bearer와 RefreshCookie 입력 항목은 표시하지 않는다. 실제 API의 Authorization Bearer 지원은 유지한다. 브라우저는 Authorize 입력으로 HttpOnly 쿠키를 설정할 수 없으므로 Access/Refresh 쿠키는 로그인·가입 응답으로 발급받는다. 재발급 요청은 브라우저가 자동 전송하는 refreshToken 쿠키를 사용한다.
