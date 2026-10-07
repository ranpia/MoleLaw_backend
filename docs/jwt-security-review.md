# JWT 인증 보안 검토

최신화: 2026-10-07. 전체 일정·API 변경 범위·구현 상태는 [백엔드 재구성 계획](backend-rebuild-plan.md)을 따른다. Redis Access 상태와 MySQL Refresh 별도 테이블 사용은 확정했으며, 아래 개선 사항은 아직 구현 완료로 간주하지 않는다.

## 검토 범위와 현재 상태
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

## 권장 저장·발급 구조
- Access는 짧은 수명(초기 제안 15분)의 JWT로 두고 sub는 사용자 ID, tokenType=access, jti, iss, aud, iat, exp, 로그인 세션 ID를 검증한다. 이메일·상담방 제목·메시지·법령 원문은 claim에 담지 않는다.
- Refresh는 고엔트로피 난수 토큰을 우선 권장한다. JWT를 유지한다면 tokenType=refresh를 필수 검증한다. 일반 인증 필터는 Refresh를 거절하고 재발급은 등록된 Refresh만 허용한다.
- 브라우저 Refresh는 HttpOnly 쿠키로만 전달하고 로그인·재발급 응답 DTO에서 제거한다. localStorage·sessionStorage에 Refresh를 저장하지 않는다. Access의 쿠키 유지 또는 프론트 메모리+Authorization 방식은 프론트 계약과 함께 선택한다. 메모리 저장도 XSS로부터 완전한 보호는 아니다.
- 저장소 역할은 Access 인증 상태를 Redis, Refresh를 MySQL 별도 테이블로 확정한다. MySQL refresh_token 테이블에는 원문 대신 토큰 해시, userId, sessionId/familyId, 만료 시각, 사용·폐기 상태, 교체 관계를 저장한다. Qdrant·채팅 메모리는 인증 저장소로 사용하지 않는다.
- 재발급 시 사용자와 세션 활성 상태를 확인하고 원자적으로 기존 Refresh를 소비·폐기한 뒤 새 Refresh와 Access를 발급한다. 소비된 토큰 재사용은 해당 family 폐기와 재로그인으로 처리한다. 동시 탭 요청과 응답 유실 재시도 정책도 정의한다.
- 로그아웃은 해당 로그인 세션, 비밀번호 변경·계정 탈퇴는 정책에 따른 전체 세션을 서버에서 폐기한다. 재발급 엔드포인트는 Access 인증 없이 Refresh 자체로 검증하되 CSRF 보호와 호출 제한을 적용한다.

## 확정 방향: Redis Access + MySQL Refresh
- Access는 서명된 JWT를 유지하고 Redis에는 토큰 원문 대신 `auth:access:{jti}` 키와 userId·sessionId 등 최소 인증 상태를 저장한다. TTL은 JWT의 남은 만료 시간과 맞춘다. 인증은 서명·용도·issuer/audience·만료 검증 후 Redis 등록 상태와 사용자·세션 일치 여부까지 확인한다.
- 활성 토큰 등록 방식(allowlist)을 사용한다. Redis 키가 없으면 유효한 서명의 JWT라도 거절한다. Access 발급 시 Redis 등록이 성공한 후에만 클라이언트로 전달하며, Redis 오류는 인증을 허용하는 우회 없이 서비스 오류로 처리한다.
- 로그인 세션별 활성 상태도 Redis에 두고 매 요청 확인한다. 세션 키의 TTL은 해당 세션의 Refresh 유효기간에 맞추며, 갱신 때 연장 정책을 명시한다. 로그아웃·재사용 탐지 시 세션 상태를 폐기해 그 세션의 여러 Access를 함께 차단한다. userId → sessionId 인덱스로 비밀번호 변경·계정 탈퇴의 전체 세션 폐기를 지원한다.
- Redis 장애·재시작·키 유실 시 기존 Access는 거절한다. MySQL의 정상 Refresh로만 사용자·세션 상태를 검증해 새 Redis 상태와 Access를 발급한다. 일반 인증 필터에서 JWT만 보고 사라진 키를 자동 복원하지 않는다. 복구 후 오래된 Redis 데이터가 폐기 세션을 되살리지 않도록 복구·재동기화 절차를 정의한다.
- Refresh 회전과 재사용 탐지는 MySQL의 원자적 상태 전이로 처리한다. Redis와 MySQL에는 단일 트랜잭션이 없으므로 발급·폐기의 순서, 실패 시 보상·재시도와 로그아웃 완료 조건을 정의한다. 폐기 동기화가 끝나지 않았다면 성공으로 응답하지 않고, 지연 동안의 허용 여부를 명시한다.
- Redis의 활성 상태만으로 아직 신고되지 않은 Access 탈취를 자동 판별할 수는 없다. 유효한 토큰이 등록된 동안에는 재사용 가능하므로 짧은 수명·노출 방지와 침해 탐지 후 세션 폐기를 함께 적용한다.
- Redis 연결 설정·자격 증명은 환경변수로 관리하고 로컬 Compose·상태 확인·테스트 구성을 추가한다. 테스트 프로필은 Redis를 모킹하거나 격리해 기존 전체 테스트가 실제 Redis 없이 실행되도록 유지하고 실제 TTL·폐기 검증은 별도 통합 테스트로 수행한다.

## Secure 설정과 탈취 대응
- 운영 HTTPS에서는 Secure=true·HttpOnly=true를 공통화한다. 동일 사이트 구성이면 SameSite=Lax/Strict를 우선 검토하고, 실제 교차 사이트 구성이 필요한 경우에만 None+Secure와 명시적 CSRF 보호를 사용한다. 로컬 HTTP 예외는 local 프로필에 한정한다.
- Refresh Path는 /api/auth처럼 재발급·로그아웃을 포함하는 범위로 좁히고 발급·삭제 시 이름·Domain·Path를 일치시킨다. Path는 전송 범위이며 보안 경계 자체는 아니다. JWT·서버 저장소·쿠키의 만료 정책을 맞춘다.
- Secure는 HTTPS 전송 제한이고 HttpOnly는 JS의 쿠키 읽기 제한이다. 이미 탈취된 Bearer Access의 재사용이나 XSS의 인증 요청 실행을 차단하지 않는다. 짧은 Access 수명과 Refresh 회전만으로 Access를 즉시 무효화할 수는 없다.
- 즉시 차단은 Redis의 Access 등록 상태와 로그인 세션 활성 상태를 매 요청 확인하는 방식으로 구현한다. 로그아웃·침해 탐지·비밀번호 변경의 폐기 범위와 Redis/MySQL 반영 실패 처리를 검증한다.
- 토큰 원문·쿠키·Authorization·JWT payload 디버그 로그를 제거하고 인증 실패 코드만 기록한다. CORS는 FRONTENDURI 기반 허용 목록으로 바꾸고 쿠키 기반 상태 변경 요청에 CSRF 토큰·Origin 검증을 구성한다. 로그인·재발급 호출 제한과 민감 작업의 재인증도 추가한다.

## 구현 우선순위와 검증
1. 토큰 로그 제거, Access/Refresh 용도 분리, CORS 허용 목록·CSRF 정책 확정.
2. Secure·SameSite·Path·만료 공통 설정과 Refresh 응답 제거(API 변경 문서화).
3. MySQL Refresh 저장소·회전·재사용 탐지·서버 폐기, 만료 Access 없이 재발급 가능하도록 인증 경로 조정.
4. Access 15분 정책, Redis 활성 토큰·세션 저장소와 TTL·폐기·장애 처리를 적용하고 일반 로그인·OAuth 동일 정책을 검증한다.

Access 15분과 Refresh 7일은 초기 제안이다. 실제 수명·Refresh 형식·갱신 시 절대/연장 만료·Access 전달 방식·SameSite·CSRF·다중 기기 정책은 구현 전 확정한다. 로그인 sessionId는 인증 폐기 단위이며 Advisor의 상담방 conversationId와 별개다. 로그아웃은 토큰을 폐기하되 채팅 기록·근거를 삭제하지 않는다.

테스트는 토큰 교차 사용 거부, 만료·잘못된 서명·issuer/audience 거부, 누락 쿠키, 삭제·폐기 사용자, Access 만료 후 Refresh 재발급, 동시 회전·재사용, 로그아웃·비밀번호 변경 후 폐기, Redis 키 누락·TTL·장애·재등록 차단, 두 저장소 부분 실패, 쿠키 발급·삭제 속성, 허용되지 않은 Origin·CSRF 요청 차단을 포함한다. 실제 브라우저의 로컬/운영 쿠키 동작은 별도 통합 검증한다.

## 참고
- [OWASP JWT 보안](https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_Cheat_Sheet.html): 토큰 노출·저장·폐기 위험.
- [RFC 9700 §4.14](https://www.rfc-editor.org/rfc/rfc9700.html#section-4.14): OAuth Refresh 회전·재사용 탐지 지침. 프로젝트 자체 로그인에도 설계 기준으로 참고한다.
- [MDN Set-Cookie](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Set-Cookie): Secure·HttpOnly·SameSite·Path 동작.
