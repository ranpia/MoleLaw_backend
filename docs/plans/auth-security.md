# 인증·보안 실행 계획

## 프론트 개발 시 확인할 사항 — CSRF 초기 요청 (2026-10-13)

상태: 브라우저에서 첫 요청 403, 재시도 성공 현상이 보고됨. 서버 직접 검증에서는 `/api/auth/csrf` 응답 token과 쿠키가 일치했고, 해당 토큰으로 로그아웃이 성공했으며 재조회 후에도 토큰이 유지됐다. 브라우저에서 발생하는 원인은 아직 확정하지 않았다.

- [ ] 앱 초기화 시 `GET /api/auth/csrf`를 완료한 뒤 상태 변경 요청을 허용한다. 매 요청마다 토큰 조회를 반복하지 않는다.
- [ ] 운영에서는 CSRF 쿠키가 HttpOnly이므로 JSON 응답의 token을 메모리에 보관하고 `X-XSRF-TOKEN` 헤더로 전송한다. 쿠키 전송에 필요한 credentials 설정과 CORS 허용 Origin을 함께 확인한다.
- [ ] 개발 Swagger의 자동 처리는 같은 Origin에서만 적용된다. Swagger 화면과 Servers 주소의 scheme/host/port 일치를 확인하고 localhost와 127.0.0.1을 혼용하지 않는다.
- [ ] Chrome에서 `XSRF-TOKEN`의 Domain·Path 중복, HttpOnly·Secure·SameSite 속성과 각 응답의 Set-Cookie를 확인한다. 같은 이름의 쿠키 중복은 Swagger 자동 헤더 처리를 방해할 수 있다. 진단 시 토큰 원문은 로그에 남기지 않는다.
- [ ] 첫 요청 403과 두 번째 성공이 재현되면 두 요청의 CSRF 헤더 존재 여부, 쿠키 전달 여부, 응답의 쿠키 발급·삭제를 비교해 원인을 확인한다. CSRF 토큰을 일회용으로 가정하지 않는다.
- [ ] 로그인·OAuth·로그아웃·브라우저 재실행 후 토큰과 쿠키 상태를 확인하고 필요한 경우 CSRF를 다시 조회한다. Access 쿠키는 강력 새로고침으로 삭제되지 않는다는 점을 구분한다.
- [ ] 모든 변경 요청을 무조건 두 번 보내지 않는다. CSRF 필터에서 거절된 403은 작업이 실행되지 않지만, 성공 응답 유실·타임아웃 후 재시도는 중복 실행될 수 있다. CSRF 오류와 권한·Origin 오류를 구분하고 자동 재시도에는 상한을 둔다.

상태: 2026-10-13 목표를 **5분 stateless Access JWT + Redis Refresh**로 변경했다. Access 수명은 기본/운영·test 5분, local 개발 편의 15시간으로 구현했다. JWT와 쿠키는 공통 `jwt.access-ttl`을 사용한다. 현재 코드는 여전히 Access Redis 등록·매 요청 확인·전달된 Access 폐기를 사용하며 stateless 전환·Refresh 서버 저장·회전은 미구현이다. 전체 순서는 [재구성 요약](../backend-rebuild-plan.md), 이전 위험 분석은 [JWT 상세 검토](../jwt-security-review.md)를 따른다.

## 범위와 확정 사항

- 운영 Access JWT 수명은 **5분(300초)**으로 확정한다. local 프로필에만 개발 편의상 15시간을 허용하며 test는 운영과 같은 5분이다. 목표에서는 서명·알고리즘·용도·issuer/audience·만료·필수 claim을 로컬 검증하며 Redis allowlist·blocklist·세션 상태를 매 요청 조회하지 않는다. `sub`는 불변 사용자 ID, `jti`는 추적 식별자이며 저장소 등록 조건이 아니다.
- Refresh와 로그인 세션/family·사용자별 인덱스는 Redis에서 해시·만료·회전 관계·폐기를 관리한다. MySQL Refresh 테이블은 만들지 않는다. 브라우저에는 HttpOnly 쿠키로만 전달하고 JSON에서 제거한다.
- 로그아웃·비밀번호 변경·재사용 탐지는 해당 범위의 Refresh를 폐기해 추가 발급을 막는다. 이미 발급한 Access는 즉시 폐기되지 않으며 남은 만료 시간, 최대 5분 동안 유효할 수 있다. 서버 시간 동기화를 유지하고 만료 유예는 기본 0초로 두며 변경 시 실제 최대 허용 시간도 문서화한다.
- Stateless는 토큰 활성 상태 검증의 특성이다. 현재 사용자 조회·API 권한·상담방 소유권 등 업무 DB 검사는 별개다. 계정 삭제·정지의 즉시 차단을 주장하지 않으며, 민감 작업은 현재 계정 상태와 재인증을 별도로 확인한다.
- 로그인 세션 ID는 Advisor의 상담방 ID와 별개다. 로그아웃으로 상담 기록을 지우지 않는다.
- 일반 로그인·가입·OAuth·재발급·로그아웃이 동일한 발급·쿠키·폐기 서비스를 사용한다.

## 구현 순서

### Access 식별자·claim 보안 준수사항

- Access JWT의 `sub`는 MySQL `User.id`를 문자열로 직렬화한 불변 사용자 고유키를 사용한다. 이메일·닉네임·OAuth provider 조합을 사용자 식별자로 넣지 않는다. `jti`는 토큰마다 달라지는 식별자로 사용자 고유키와 구분한다.
- 이메일은 다른 claim에도 넣지 않는다. 닉네임·프로필·상담 내용·상담방 목록·비밀값을 포함하지 않고 인증에 필요한 최소 claim만 발급한다. 사용자 고유키도 외부에 노출 가능한 식별자이며 비밀값이나 접근 권한 증명 자체로 취급하지 않는다.
- 현재 `JwtUtil`은 `sub=email:provider`를 발급하고 필터·사용자 조회도 이 형식에 의존한다. **사용자 고유키 전환은 미구현이며 필수 보안 작업**이다. 가입·일반 로그인·OAuth·재발급의 발급 경로와 principal·사용자 조회를 함께 변경한다. 이메일은 로그인 입력과 사용자 DB에 유지한다.
- 서명·용도·issuer/audience·만료 검증 후 `sub`의 형식·범위를 검사한다. 사용자 고유키만 일치한다고 권한을 부여하지 않고 API 권한·상담방 소유권을 별도로 검사한다. 사용자 조회가 필요한 경로는 이메일 분해 대신 ID로 조회한다.
- 이전 `email:provider` Access는 새 토큰 버전/키 전환 시 거절하고 재로그인을 요구한다. 새 형식에서 이메일 subject를 자동 변환해 허용하지 않는다. 이메일 변경 후에도 사용자 고유키는 유지된다.
- 완료 검증: 모든 발급 경로의 `sub=User.id`, 이메일/provider 개인정보 claim 부재, 잘못된 ID·구형 subject 거부, 정상 사용자 조회·권한·소유권 회귀를 검사한다. 토큰 원문은 테스트 실패 메시지나 로그에 출력하지 않는다. local/test도 동일한 식별자 정책을 적용한다.

### 단계별 작업

1. **즉시 개선:** `JwtUtil`, `JwtAuthenticationFilter`, `OAuth2SuccessHandler`의 토큰·payload 로그 제거. Access/Refresh 용도 분리, Origin 허용 목록·CSRF 계약을 적용한다.
2. **API 계약:** Refresh 응답 필드 제거, 만료 Access 없이 Refresh로 재발급, 쿠키 발급·삭제 속성 통일. 프론트 재발급·로그아웃 흐름과 함께 변경한다.
3. **Redis Refresh 저장소:** `auth` 도메인에 토큰 해시·session/family·사용자별 인덱스와 TTL을 구현한다. 원문 토큰을 Redis·로그에 저장하지 않는다. 운영 영속화·메모리·복구 정책을 확정한다.
4. **회전·폐기:** Redis Lua 등으로 검증·기존 토큰 소비·새 해시 등록을 원자적으로 처리한다. 로그아웃과 회전이 경합해도 폐기 family를 되살리지 않는다. 재사용 탐지·기기별/전체 Refresh 폐기·응답 유실 처리를 구현한다.
5. **Access 전환:** 구현된 운영 JWT·쿠키 300초 정책을 유지하며 Redis Access 등록·인증·폐기 경로를 제거한다. local 15시간은 개발 편의 예외다. 일반 로그인·가입·OAuth·재발급을 함께 전환하고 issuer/audience를 검증한다. `sub=email:provider`를 `sub=User.id`로 변경하고 위 claim 보안 준수사항을 충족한다. Refresh 저장·회전 검증 전에 Access만 stateless로 바꾸지 않는다.

## 구현 전 결정할 정책

| 항목 | 초기 제안·결정 조건 |
| --- | --- |
| 수명·형식 | 운영 Access 5분, local 개발 15시간, test 5분. Refresh는 고엔트로피 난수, family 절대 수명 7일을 초기 제안으로 두며 연장 허용 여부는 구현 전 결정한다. 회전으로 절대 수명이 무한 연장되지 않아야 한다. |
| Access 전달 | 쿠키 또는 메모리+Authorization 중 프론트 계약으로 선택. 양쪽 허용 시 충돌 처리도 정의한다. |
| 쿠키·CSRF | 운영 사이트 관계에 맞춘 SameSite, Secure, Path와 CSRF 토큰·Origin 검사. 로컬 HTTP 예외는 local에 한정한다. |
| 다중 탭·응답 유실 | 프론트 재발급 요청을 합친다. 서버는 한 Refresh의 한 번 소비를 보장한다. 초기에는 소비 후 응답 유실 시 재로그인을 요구하며, 유예 처리는 탈취 토큰 재사용 위험과 함께 별도 결정한다. |
| 세션·보관 | 기기별/전체 로그아웃의 서버 폐기 대상은 Refresh family다. 최대 활성 family·인덱스 정리·감사 이벤트 보관을 정의한다. 소비된 해시와 폐기 tombstone은 해당 family 절대 만료까지 유지해 재사용을 탐지한다. |
| 키·기존 토큰 | 전환 전 발급한 15시간 Access가 계속 허용되지 않도록 새 서명 키 또는 명시적 새 토큰 버전으로 구분해 이전 형식을 거절한다. 기존 미등록 Refresh는 이관·자동 등록하지 않고 재로그인을 요구한다. 구버전 노드가 토큰을 계속 발급하지 않도록 배포 전환을 조정한다. |

## 장애·동시성 보완

- Redis 장애 시 기존 Access의 암호학적 검증은 계속 가능하다. 업무 DB와 별도 CSRF 처리의 의존성은 남는다. 로그인·OAuth·재발급은 Refresh 상태 저장/회전이 성공하기 전 토큰을 전달하지 않고 저장소 오류는 503으로 처리한다. 로그아웃도 서버 폐기를 확인하지 못하면 성공으로 표시하지 않는다.
- Redis에 없는 Refresh는 401로 거절하고 JWT 서명이나 MySQL 사용자 존재만으로 복원하지 않는다. 데이터 유실 시 재로그인하며 기존 Access는 원래 만료까지 유지된다. MySQL 백업으로 Refresh를 복구하지 않는다.
- 소비·발급·family 폐기는 동일 원자 연산 경계에서 경합을 처리한다. Redis Cluster 사용 시 관련 키의 hash slot 배치를 먼저 설계한다. 사용자 전체 폐기와 새 로그인 경합도 순서·세대 관리로 정의한다.
- 회전 후 응답 유실 시 이전 Refresh 자동 재시도를 허용하지 않는 초기 정책을 사용한다. 프론트는 다중 탭을 포함해 재발급을 합치고 무한 재시도하지 않는다. 유예/idempotency 지원은 별도 보안 설계 후 도입한다.
- 현재 로컬 Redis는 RDB/AOF 비활성·tmpfs다. 목표 전환 후 재시작 시 Refresh가 유실되므로 재로그인한다. 운영은 전용 용량·noeviction·인증/TLS·영속화·복제와 장애 전환을 검증한다. noeviction의 쓰기 실패도 발급 실패로 처리한다.
- 오래된 Redis 상태 복원은 소비·폐기된 Refresh를 부활시킬 수 있다. 복구 신뢰성을 보장하지 못하면 해당 인증 namespace를 무효화해 재로그인을 요구한다. 정상 재시작과 의심스러운 복원/장애 전환을 구분하며 AOF·복제만으로 최근 쓰기 보존을 단정하지 않는다. [Redis 영속화](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/), [복제](https://redis.io/docs/latest/operate/oss_and_stack/management/replication/)
- 사용자 생성/비밀번호 변경/탈퇴의 MySQL 작업과 Redis 폐기는 단일 트랜잭션이 아니다. 순서·부분 실패·재시도 정책을 정의하고 성공하지 않은 전체 폐기를 성공으로 응답하지 않는다. 이 처리가 기존 Access의 즉시 무효화를 보장하지는 않는다.
- 인증 실패는 401, 권한 부족은 403, 저장소 장애는 서비스 오류로 구분한다. 프론트가 장애를 토큰 만료로 오인해 무한 재발급하지 않도록 재시도 상한·오류 코드를 맞춘다.
- 원문 토큰 없는 보안 이벤트와 회전 충돌·재사용 탐지·폐기 지연·Redis 오류 지표를 기록한다. 로그인·재발급 호출 제한과 민감 작업 재인증을 포함한다.

## 완료 조건

- [ ] 모든 Access 발급 경로가 `sub=User.id`를 사용하고 이메일·provider·불필요한 개인정보 claim을 포함하지 않는다. 구형 subject 거부와 ID 기반 principal·권한·소유권 검증을 통과한다.
- [ ] 교차 토큰 사용·잘못된 서명/용도/issuer/audience·만료·삭제 사용자를 거절한다.
- [ ] 만료 Access로 재발급할 수 있고 동시 Refresh 소비는 한 번만 성공한다. 재사용 시 해당 family를 폐기한다.
- [ ] 로그아웃·비밀번호 변경 후 Refresh는 거절되고 기존 Access는 만료 전 허용·300초 경계부터 거절된다. 탈퇴 사용자 접근은 업무 계정 조회 정책으로 별도 검증한다. 회전/폐기 경합과 계정 변경의 부분 실패를 재현한다.
- [ ] Redis 장애에서도 일반 Access 검증은 Redis를 호출하지 않는다. 로그인·재발급·폐기는 장애 시 503이다. 키 유실 시 Refresh는 거절하며 자동 부활하지 않는다. 실제 Redis TTL·동시 소비·재사용·장애 복구를 별도로 검증한다.
- [ ] Redis Access 등록·조회·폐기 의존성을 제거하고 JWT·쿠키 300초 일치, 이전 15시간 Access 및 미등록 Refresh 거절을 검증한다.
- [ ] 쿠키·CORS·CSRF와 일반 로그인/OAuth를 실제 브라우저에서 검증한다. 외부 저장소 없는 단위 테스트도 유지한다.
- [ ] API 변경·전환 재로그인·키 교체·복구 절차를 문서화한다. 상담방 소유권 검사는 인증과 별도로 유지한다.

## 1단계 구현과 프론트 전환 계약 (2026-10-13 당시)

아래는 Redis Access 등록을 추가하기 전 1단계의 이력이다. 당시의 로그아웃·테스트 결과를 현재 구현이나 새 목표와 혼동하지 않는다. 현재 구현은 마지막 절, 확정 목표는 위 범위·완료 조건을 따른다.

- 회원가입 성공은 `200`과 `{"success":true,"data":"회원가입 되었습니다."}` 및 JWT 쿠키를 반환한다. 기존 백엔드 `/Main` 리다이렉트는 제거했으며 프론트가 성공 응답을 받은 뒤 화면을 이동한다. 회원가입은 인증 없이 접근 가능하지만 CSRF 토큰과 Origin 정책은 적용한다.

- JWT 원문·payload·파싱 예외 로그를 제거했다. `tokenType=access/refresh`와 HS256 서명, 만료, 필수 claim을 검증하며 교차 사용과 용도 없는 기존 토큰은 거절한다. 배포 후 기존 사용자는 다시 로그인해야 한다.
- CORS는 `security.allowed-origins` (`CORS_ALLOWED_ORIGINS`, 쉼표로 구분한 명시적 Origin)만 허용한다. 생략하면 단일 `FRONTENDURI`를 사용한다. OAuth 리다이렉트용 `FRONTENDURI`에는 목록을 넣지 않는다.
- 프론트는 먼저 `GET /api/auth/csrf`를 `credentials: "include"`로 호출한다. 응답 `{token, headerName}`의 토큰을 메모리에 두고 모든 POST/PUT/PATCH/DELETE 요청에 해당 헤더(기본 `X-XSRF-TOKEN`)와 credentials를 보낸다. 로그인·가입·재발급·로그아웃에도 필요하다. 토큰을 담은 `XSRF-TOKEN` 쿠키는 local/test에서만 JavaScript로 읽을 수 있고 운영에서는 HttpOnly다. Access/Refresh 인증 쿠키는 HttpOnly를 유지한다.
- 상태 변경 요청의 Origin은 같은 서버 주소이거나 외부 Origin 허용 목록과 일치해야 한다. Origin이 없는 클라이언트도 CSRF 토큰을 제출해야 한다. 누락·잘못된 CSRF 또는 허용되지 않은 Origin은 403이다. Bearer 호출도 동일한 CSRF 계약을 따른다.
- Access는 Authorization Bearer를 우선하고 없을 때 accessToken 쿠키를 사용한다. Refresh로 일반 API를 인증하거나 Access로 재발급할 수 없다. `/api/auth/reissue`는 Access 인증 없이 Refresh 자체와 사용자 존재를 검증한다.
- 일반 로그인·가입·OAuth·재발급·탈퇴·로그아웃은 공통 쿠키 설정을 사용한다. 운영 기본은 Secure=true, SameSite=None, HttpOnly=true이며 `COOKIE_SAME_SITE=Lax/Strict`로 같은 사이트 정책을 선택할 수 있다. Secure=false는 local/test만 허용하고 None과 함께 사용할 수 없다. local/test는 Lax다.
- Access Path=/, Refresh Path=/api/auth로 통일했다. Refresh 발급·삭제 시 이전 Path=/ 쿠키도 삭제한다. 쿠키 수명은 현재 JWT와 같은 Access 15시간, Refresh 7일이다. 목표 Access 5분 변경은 stateless/Redis Refresh 전환 단계에서 적용한다.
- Refresh JSON 응답 제거, 서버 저장소·회전·폐기와 issuer/audience 검증은 후속 단계다. 현재 로그아웃은 쿠키만 제거하므로 복사한 JWT의 서버 폐기를 제공하지 않는다.

자동 테스트와 별도로 배포 전 실제 브라우저에서 로컬 HTTP 및 운영 HTTPS의 가입·로그인·OAuth 리다이렉트·재발급·로그아웃, 쿠키 전달과 삭제, CSRF 403 후 재조회 흐름을 확인해야 한다. 프론트의 CSRF 헤더 적용과 함께 배포한다.
검증:  .\gradlew.bat test  전체 성공. JWT·인증 필터·재발급·쿠키·CORS·CSRF 회귀 테스트 포함. 실제 브라우저 검증은 미수행.

Swagger 같은 Origin 요청도 CSRF 토큰을 검증한다. 외부 Origin은 명시적 허용 목록으로 제한한다. 내부 ERROR 디스패치는 인증 없이 처리해 CSRF/Origin 거절의 403이 401로 덮어써지지 않도록 한다. 직접 /error 접근과 일반 보호 API의 인증 요구는 유지한다.

개발 local 및 문서 검증용 test의 Swagger UI는 springdoc.swagger-ui.csrf.enabled=true와 cookie-name=XSRF-TOKEN, header-name=X-XSRF-TOKEN을 사용한다. 같은 서버에서 GET /api/auth/csrf를 한 번 실행하면 JavaScript로 읽을 수 있는 CSRF 쿠키가 설정되고 이후 요청 헤더에 자동 적용된다. 수동 헤더 입력과 Authorize CSRF 항목은 없다.

Swagger Authorize에는 AccessCookie만 표시하며 Bearer, RefreshCookie, CsrfToken 입력 항목은 표시하지 않는다. 실제 API의 Authorization Bearer 지원은 유지한다. 브라우저는 Authorize 입력으로 HttpOnly 쿠키를 설정할 수 없으므로 Access/Refresh 쿠키는 로그인·가입 응답으로 발급받는다. 재발급 요청은 브라우저가 자동 전송하는 refreshToken 쿠키를 사용한다.

자동 처리 근거: https://springdoc.org/v2/properties.html 및 https://springdoc.org/faq.html. local/test의 CSRF 쿠키만 HttpOnly=false로 바꾸며 Secure/SameSite/Path 정책과 서버의 CSRF 검증은 유지한다. Swagger와 API는 같은 Origin을 사용한다. 별도 Origin의 프론트는 기존 JSON 토큰 조회와 헤더 전송 계약을 사용한다.

운영 분리 규칙: 공통/main 배포 기본값은 Swagger UI·API docs·Swagger CSRF 자동 처리 비활성화다. 문서 경로는 local/test 외에서는 인증 여부와 무관하게 차단한다. local에서만 개발 편의 설정을 활성화하고 test는 문서 검증용 예외다. cookie.csrf-http-only는 기본 true이며 false는 local/test에서만 허용한다. 운영 프론트는 /api/auth/csrf JSON의 token을 메모리에서 읽어 헤더로 전송한다. 운영 배포에 local/test 프로필을 사용하지 않는다.

## 현재 Redis Access 구현 — 전환 전 상태 (2026-10-13)

- `AccessTokenService`를 통해 가입·로그인·OAuth·재발급의 Access를 발급한다. JWT에 고유 UUID `jti`를 넣고 `molelaw:auth:access:{jti}` → `email:provider`를 남은 JWT 수명으로 등록한다. `SET NX`와 만료를 한 명령으로 적용하며 JWT 원문·Refresh는 Redis에 저장하지 않는다. Access는 기본/운영·test 5분, local 15시간이며 Refresh 7일과 JSON 계약은 유지한다. TTL은 해당 JWT의 실제 만료에 맞춰진다.
- 인증 필터는 서명·용도·만료·jti를 검증한 뒤 Redis의 사용자 일치를 확인하고 DB에서 사용자 존재를 확인한다. 키 누락·사용자 불일치·폐기는 401이다. 일반 요청에서 키를 자동 재등록하지 않는다. 탈퇴한 사용자는 DB 조회에서도 거절한다.
- Redis 조회·등록·폐기 오류는 503 `AUTH_STORE_UNAVAILABLE`이다. 발급 등록 실패 시 JWT를 반환하지 않고 가입 DB 변경도 롤백한다. OAuth 등록 실패도 JWT 쿠키·리다이렉트 없이 503을 반환한다. JWT만으로 인증하는 대체 경로는 없다. CSRF 조회와 Refresh 재발급은 전달된 이전 Access의 Redis 조회를 생략하며 재발급의 새 Access 등록은 반드시 수행한다.
- 로그아웃은 전달된 Bearer 및 Access 쿠키가 가리키는 jti를 폐기한 뒤 JWT 쿠키를 삭제한다. 다른 Access는 유지하고 Refresh 서버 폐기는 아직 없다. **복사된 Refresh로 로그아웃 후에도 새 Access를 받을 수 있으므로 전체 세션 로그아웃이 아니다.** Redis 폐기 실패는 503이며 성공/쿠키 삭제로 처리하지 않는다.
- Redis 재시작·유실 후 기존 Access는 거절한다. 현재는 유효한 Refresh JWT와 DB 사용자 존재 확인으로 새 jti를 등록한다. 이는 전환 전 동작이며 목표 Redis Refresh 저장은 미구현이다.
- 연결 변수는 `.env.example`의 `REDIS_HOST/PORT/PASSWORD/DATABASE/SSL_ENABLED`다. 로컬은 Compose의 비밀번호와 일치시킨다. Redis 연결·명령 제한 시간은 각각 2초다. 운영 Redis 연결·네트워크·비밀값 설정은 배포 전 별도 준비하며 운영에 local/test를 사용하지 않는다.
- 일반 테스트는 `src/test`의 대체 저장소 또는 모킹을 사용해 Docker 없이 실행한다. `redisLiveTest`는 환경변수 또는 Git 제외 `docker/.env.compose`로 로컬 Redis에 접속하고 고유 테스트 키만 생성·삭제한다. 전체 테스트 95개·bootJar 및 실제 Redis TTL·사용자 일치·폐기 검증을 통과했다. Swagger UI의 수동 브라우저 확인은 수행하지 않았다.

후속 범위: Redis Refresh 해시·회전·재사용 탐지·폐기와 사용자별 family 인덱스, Access 5분 stateless 전환 및 issuer/audience 검증, 기존 토큰 전환·장애 복구. 현재 Access jti 구현의 검증 이력을 새 설계의 구현·검증 완료로 간주하지 않는다.

## 환경별 Access 수명 구현·검증 (2026-10-13)

- `application.yml`의 `jwt.access-ttl=5m`이 기본/운영·test에 적용된다. `application-local.yml`은 개발 편의상 15h이며 local & !test 조건에서만 활성화된다. JWT 발급·Access 쿠키가 같은 속성을 사용하고 Redis TTL은 발급 JWT 만료에 맞춘다. Refresh는 기존 7일이다.
- 설정 변경은 새로 발급하는 Access에 적용된다. 기존 15시간 JWT의 exp와 Redis TTL을 소급 변경하지 않는다. stateless 전환 시 이전 토큰 거절은 별도 전환 작업이다.
- Java 17에서 `gradlew.bat test --no-daemon` 전체 100개 통과. 운영·local·test·local+test의 실제 발급 JWT 수명과 쿠키 Max-Age 일치, Refresh 7일 유지 및 잘못된 수명 설정 거절을 추가 검증했다. 기존 OpenAPI 생성·보안 회귀 테스트도 포함한다. 실제 브라우저/운영 배포 검증은 수행하지 않았다.
