# JWT 인증 보안 검토

검토 기준: 2026-10-07의 개선 전 코드. **아래 위험 표는 현재 구현 상태가 아니다.** 이후 토큰 용도 검증·로그 제거·CORS/CSRF·쿠키 공통화·Redis Access 등록과 폐기가 적용됐다. 최신 계약과 미구현 세션·Refresh 정책은 [인증·보안 실행 계획](plans/auth-security.md)을 따른다. 이 문서는 기존 위험과 설계 근거를 보존한다.

## 개선 전 코드 검토
소스 기준 정적 검토이며 브라우저·배포 환경 재현이나 공격 테스트는 수행하지 않았다. 프론트엔드 저장 방식과 프록시 로그 설정은 이 저장소만으로 확인할 수 없다.

| 항목 | 확인한 동작 | 영향 |
| --- | --- | --- |
| 토큰 수명 | JwtUtil의 Access는 15시간, Refresh는 7일 | Access 탈취 시 장시간 사용 가능 |
| 토큰 용도 | 같은 서명 키·subject·검증 함수를 사용하고 token type 검증 없음 | Refresh를 Bearer로 일반 API에 사용하거나 Access를 refreshToken 쿠키에 넣어 재발급 가능 |
| Refresh 저장 | 브라우저 쿠키와 로그인·재발급 JSON 응답에 포함, 서버 저장소 없음 | HttpOnly 쿠키 외에도 JS가 응답에서 읽을 수 있고 서버 폐기·재사용 탐지 불가 |
| 재발급 | 같은 Refresh를 유지하고 Access만 발급, 사용자 존재 확인 없음 | 회전 없음. 삭제된 사용자 토큰으로도 발급 자체는 가능하며 일반 API 사용자 조회는 별도 실패 |
| 재발급 접근 | /api/auth/reissue도 anyRequest().authenticated() 대상 | 만료된 Access와 정상 Refresh만 있는 요청은 서비스 도달 전에 401 가능 |
| 로그아웃 | 쿠키 삭제만 수행 | 이미 복사된 Access·Refresh는 만료 전까지 계속 사용 가능 |
| 토큰 로그 | JwtAuthenticationFilter와 OAuth2SuccessHandler가 토큰 원문 출력 | 로그 접근자가 인증 정보를 획득 가능 |
| 쿠키 | HttpOnly 적용, Domain 미지정, Path=/ | Refresh도 모든 경로에 전송됨 |
| 설정 일관성 | OAuth만 cookie.secure 사용, 일반 로그인·재발급·가입은 true 고정 | 로컬 COOKIE_SECURE=false가 모든 경로에 적용되지 않음 |
| SameSite·수명 | 가입 Lax·7일, 공통 유틸 None·1일 | 로그인 경로별 정책 불일치, JWT 만료와 쿠키 수명 불일치 |
| CSRF·CORS | CSRF 비활성화, allowedOriginPatterns=*와 allowCredentials=true | 쿠키 인증에서 임의 Origin의 인증 요청·응답 접근을 허용하는 구성. 브라우저 쿠키 정책에 따라 실제 영향은 달라짐 |

## 확정 방향: Access 5분 stateless + Redis Refresh

2026-10-13 사용자 결정으로 기존 Redis Access + MySQL Refresh 목표를 대체한다. 현재 코드는 Redis Access 등록 방식이다. 운영/test Access 5분·local 개발 Access 15시간과 JWT/쿠키 공통 설정은 구현했지만 stateless/Redis Refresh 전환은 미구현이다. 구현·전환·완료 조건의 기준은 [인증 실행 계획](plans/auth-security.md)이다.

- Access는 **5분(300초)** JWT다. 허용 알고리즘·서명·용도·issuer/audience·iat/exp·필수 claim을 로컬 검증하는 목표다. `sub`는 `User.id`를 문자열로 표현한 불변 사용자 고유키, `jti`는 토큰별 추적 식별자로 두고 목표에서는 Redis 등록·세션 조회를 요구하지 않는다. 2026-10-14 Access와 현재 JWT Refresh의 `sub=User.id`, 버전 2, 개인정보 claim 제외와 ID 기반 principal·조회를 구현했다. 현재 Redis Access 확인과 issuer/audience 미검증은 후속 전환 대상이다. 고유키 자체로 권한을 부여하지 않는다.
- Refresh는 고엔트로피 난수 토큰을 우선 제안한다. Redis에는 원문 대신 해시·userId·session/family·만료·소비·폐기·교체 관계를 저장한다. 사용자별 인덱스는 기기별/전체 Refresh 폐기에 사용한다. MySQL Refresh 테이블은 추가하지 않는다.
- 회전은 Lua 등으로 기존 토큰 검증·한 번 소비·새 해시 등록을 원자적으로 수행한다. 소비 토큰의 재사용은 family 폐기와 재로그인으로 처리한다. 소비 이력을 family 절대 만료까지 유지하고 다중 탭·응답 유실·회전/로그아웃 경합을 검증한다.
- Refresh는 HttpOnly 쿠키로만 전달하고 JSON에서 제거한다. Access 전달 방식은 프론트 계약으로 결정한다. 쿠키 사용 시 CSRF 방어가 계속 필요하다.
- 로그아웃·비밀번호 변경·침해 탐지는 Refresh의 추가 발급을 차단한다. 이미 발급된 Access는 **최대 5분** 만료까지 유효할 수 있으며 개별 즉시 폐기를 제공하지 않는다. 민감 작업의 계정 상태 확인·재인증과 상담방 소유권 검사는 별개다.

## 선택 이유와 운영 한계

매 요청 Redis 활성 상태를 확인하면서 JWT와 서버 상태를 함께 관리하는 복잡성을 줄이고 Access 인증을 Redis 장애와 분리한다. 대신 즉시 Access 폐기 기능을 포기하고 짧은 수명으로 노출 시간을 제한한다. 계정·권한 조회 등 업무 DB 의존성이 자동으로 제거되는 것은 아니다.

Redis 장애 시 기존 Access 검증은 유지하지만 로그인·재발급·폐기는 503으로 처리한다. Refresh 키 누락은 401이며 사용자 존재나 JWT 서명만으로 재등록하지 않는다. 비영속 Redis 데이터 유실 시 재로그인이 필요하다. 오래된 복원 데이터가 폐기 토큰을 되살릴 수 있으므로 운영 영속화·복제·복구 신뢰성을 검증하고 불확실한 인증 상태는 무효화한다.

## 쿠키·보안 계약

- 운영 Secure=true·HttpOnly=true와 명시적 Origin 허용 목록·CSRF 검증을 유지한다. Swagger/API docs 및 CSRF 자동 처리는 local과 문서 검증용 test만 허용한다. 운영 CSRF 쿠키는 HttpOnly이며 프론트는 `/api/auth/csrf` JSON의 token을 헤더로 보낸다.
- Refresh Path=/api/auth이며 발급·삭제 속성을 맞춘다. Access 쿠키 수명도 JWT와 함께 300초로 전환한다. Secure·HttpOnly는 이미 탈취된 토큰의 재사용이나 XSS의 인증 요청 실행을 막지 못한다.
- Refresh 회전·TTL·family 절대 만료를 맞추고 무한 연장을 방지한다. 토큰 원문·쿠키·Authorization·payload는 로그에 기록하지 않는다. 재발급 호출 제한과 민감 작업 재인증을 적용한다.

## 구현 순서와 검증

1. 현재 CORS·CSRF·공통 쿠키 정책을 유지하면서 Redis Refresh 해시·family·TTL 저장소를 구현한다.
2. 원자 회전·재사용 탐지·기기별/전체 폐기, 로그인·OAuth 공통 발급 및 Refresh JSON 제거를 구현한다.
3. Access 5분 stateless로 전환하고 Redis Access 등록·조회·폐기 의존성을 제거한다. 기존 15시간 Access와 미등록 Refresh는 새 키/토큰 버전으로 거절하고 재로그인한다. 구버전 서버와의 배포 경합을 통제한다.
4. JWT 300초 만료 경계, 로그아웃 후 기존 Access의 남은 유효기간, Refresh 거부, Redis 장애·유실·복원·TTL·원자 회전과 계정 변경 부분 실패를 검증한다. 실제 브라우저 쿠키·CSRF 검증은 별도로 한다.

Access 5분과 저장소 역할은 확정이다. Refresh 7일 절대 수명·난수 형식은 초기 제안이며 연장·최대 기기·감사 보관은 구현 전 확정한다. 로그인 session/family는 Refresh 관리 단위이며 상담방 conversationId와 별개다. 로그아웃은 상담 기록을 삭제하지 않는다.

## 참고

- [OWASP JWT 보안](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html): 토큰 노출·저장·폐기 위험.
- [RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14): OAuth Refresh 회전·재사용 탐지 지침. 프로젝트 자체 로그인에도 설계 기준으로 참고한다.
- [MDN Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie): Secure·HttpOnly·SameSite·Path 동작.
