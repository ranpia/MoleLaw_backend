# 클래스 책임 분리 검토

기준: 2026-11-09, PR #13 병합 이후. controller/service/config/dto/dao/entity/util 배치를 유지한다. 중첩 타입·익명 구현을 검사하고, 별도 생성/실행 책임이나 다른 클래스가 공유하는 계약을 우선 분리했다.

## 분리한 책임

| 기존 소유자 | 분리한 클래스 | 책임 |
| --- | --- | --- |
| SecurityConfig | config.security.ApiCorsProcessor | CORS 거절을 공통 오류 응답 경계로 전달 |
| SecurityConfig | CorsSecurityConfig | 허용 Origin·헤더·메서드 설정 |
| SecurityConfig | CsrfSecurityConfig | CSRF 쿠키 속성과 운영 HttpOnly 제한 |
| SecurityConfig | OAuth2ClientConfig | OAuth 토큰 클라이언트 Bean |
| SecurityConfig | config.security.SwaggerAccessPolicy | 개발/테스트 프로필과 문서 활성화 여부에 따른 접근 판단 |
| ChatStreamMvcConfig | ChatStreamAsyncConfigurer | 비동기 MVC 타임아웃·쓰기 실행기 연결 |
| FollowUpCaseToolsFactory.Session | service.answer.FollowUpCaseSession | 한 상담 턴의 동의·판례 도구 실행·읽은 근거·예산 |
| CaseSearchPipeline.State | service.retrieval.CaseSearchState | 한 검색 턴의 중복 방지·본문 캐시·진행/호출 상태 |
| RefreshTokenService.Grant | dto.security.RefreshTokenGrant | 발급 결과 계약. toString은 토큰 값을 숨김 |
| JwtUtil.AccessTokenIdentity | dto.security.AccessTokenIdentity | JWT에서 검증한 식별 정보 계약 |

SecurityConfig는 필터 체인과 인증 기반 Bean을 연결한다. CorsFilter는 기존처럼 보안 체인에 한 번만 추가하고 별도 서블릿 필터 Bean으로 자동 등록하지 않는다. JWT request SecurityContextRepository의 정적 Bean 선언을 유지해 필터 생성의 순환 의존성을 피한다. 보안 프로필·허용 목록·쿠키·인증·CSRF 정책은 유지한다.

판례 도구 Factory는 생성만 맡고 Session이 실행을 맡는다. Session과 검색 State는 요청마다 새로 생성하며 Spring singleton Bean으로 등록하지 않는다. 검색 상태 필드는 같은 retrieval 패키지에서만 직접 다루고 외부 호출자는 기존의 제한된 상태 설정/조회 메서드를 사용한다. 도구 이름·파라미터·근거 검증·예산·저장 시점은 유지한다.

## OAuth 토큰 클라이언트 교체

DefaultAuthorizationCodeTokenResponseClient 대신 RestClientAuthorizationCodeTokenResponseClient를 사용한다. 기존 수동 토큰 요청 변환기를 제거하고 Google/Kakao 등록에 표준 client_secret_post를 명시한다. Kakao의 이전 POST 설정도 교정한다. 기본 변환기가 client_id·client_secret·code·redirect_uri 및 PKCE code_verifier를 처리한다.

모의 HTTP로 두 공급자의 form 본문·Authorization 헤더 부재·PKCE·redirect URI·토큰 응답 및 invalid_grant 오류를 검증한다. 실제 Google/Kakao 로그인이나 실제 비밀값을 테스트에 사용하지 않는다. 공식 기준은 [Spring Security 6.5 client authentication](https://docs.spring.io/spring-security/reference/6.5/servlet/oauth2/client/client-authentication.html)과 [RestClient 토큰 클라이언트 API](https://docs.spring.io/spring-security/reference/6.5/api/java/org/springframework/security/oauth2/client/endpoint/RestClientAuthorizationCodeTokenResponseClient.html)를 따른다.

## 소유자 안에 유지한 타입

- LawVectorClient.EmbeddingSession, LegalToolContext.EmbeddingTime, EmbeddingWorkLimiter.Permit은 소유 객체의 검증 상태·시간/동시성 허가 수명과 결합된 핸들이다. 별도 공유 DTO나 독립 서비스로 노출하지 않는다.
- LegalEvidenceCollector.PageCursor 및 RefreshTokenService.Descriptor는 한 구현에서만 쓰는 비공개 실행/파싱 상태다.
- DTO/엔티티의 작은 enum과 record, 문서용 제네릭 envelope는 해당 계약의 일부로 유지한다. 일괄 분리로 HTTP JSON·OpenAPI 이름을 바꾸지 않는다.
- HTML 파서 콜백, Reactor sink/구독 관찰 콜백, Jackson TypeReference는 외부 API에 전달하는 짧은 어댑터다. 별도 상태·재사용 책임이 없는 콜백은 실행 위치에 유지한다.

검증 결과는 [검증 현황](../validation/validation-status.md)에 기록한다. 구조 분리 검증과 실제 OAuth 공급자 통합 검증은 구분한다.
