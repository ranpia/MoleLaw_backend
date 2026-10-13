---
name: swagger-controller-docs
description: MoleLaw 컨트롤러와 API DTO를 추가하거나 수정할 때 Swagger/OpenAPI 설명, 요청 및 응답 샘플, 인증 계약을 작성하고 검증한다. 기존 컨트롤러의 API 문서 보완에도 사용한다.
---

# 컨트롤러 Swagger 문서

모든 HTTP 컨트롤러의 공개 엔드포인트가 Swagger UI에서 설명과 실제 계약에 맞는 샘플을 제공하도록 한다. 한국어로 작성한다. 변경하는 엔드포인트의 문서도 같은 작업에서 갱신한다. 전체 컨트롤러 문서화를 요청받으면 모든 매핑을 조사해 누락 없이 적용한다.

## 작성 기준

- Swagger UI·OpenAPI 엔드포인트 노출과 Swagger의 CSRF 자동 처리는 개발용 `local`에서만 활성화한다. 공통 설정과 `main` 배포는 UI·API docs·Swagger CSRF 자동 처리를 기본 비활성화한다. `test` 활성화는 자동 문서 검증용 예외이며 배포 프로필로 사용하지 않는다.
- Swagger를 위해 운영 보안 정책을 완화하지 않는다. CSRF 쿠키의 JavaScript 읽기 허용은 `local`·`test`로 제한하고 운영은 HttpOnly를 유지한다. 운영 프론트는 CSRF 조회 JSON의 토큰을 헤더로 전송한다. Access/Refresh 쿠키의 HttpOnly는 모든 환경에서 유지한다. 운영 문서 경로는 인증 사용자에게도 차단한다.

- 컨트롤러에 `@Tag(name, description)`, 각 HTTP 매핑에 `@Operation(summary, description)`을 작성한다. 설명에는 목적, 입력 조건과 주요 동작을 포함한다.
- path/query/header 파라미터는 `@Parameter`로 의미, 필수 여부, 형식과 `example`을 제공한다. 기본값·범위·enum은 실제 바인딩과 검증 규칙에 맞춘다.
- 요청 DTO 필드에는 `@Schema(description, example)`을 작성하고 필수 여부·제약·nullable을 실제 Jackson 및 Bean Validation 동작과 일치시킨다. JSON 요청 본문에는 OpenAPI `@RequestBody`의 `@Content`·`@ExampleObject`로 복사해서 사용할 수 있는 전체 예시를 제공한다. Spring MVC `@RequestBody`와 이름이 충돌하면 OpenAPI 쪽을 완전한 클래스명으로 작성한다.
- 각 성공 응답과 실제로 발생하는 주요 실패 응답을 `@ApiResponses`에 기술한다. 본문이 있는 응답은 상태 코드별 schema, media type, `@ExampleObject`를 제공한다. 여러 동작이 다른 형태를 반환하면 이름 있는 예시로 구분한다.
- 응답 DTO 필드에도 설명과 예시를 제공한다. `ApiResponse<T>`의 `success/data/error`, 컬렉션 및 제네릭 내부 데이터가 OpenAPI에 드러나게 한다. 예시만 작성하고 schema를 빈 객체로 남기지 않는다.
- 응답 본문이 없는 204, 리다이렉트, 파일, 스트리밍은 JSON을 꾸며 넣지 않는다. 실제 content type·헤더·전달 형식을 문서화하고 해당 형식에 적절한 예시를 제공한다. 본문이 없는 요청에는 불필요한 요청 JSON을 만들지 않는다.
- 인증은 `SecurityConfig`와 `SwaggerConfig`를 확인한다. 보호된 API에는 실제 보안 스키마를 연결하고 공개 API가 전역 보안 요구 때문에 인증 필수로 표시되지 않도록 한다. 쿠키와 Bearer 지원·우선순위, CSRF가 필요한 요청의 헤더와 `/api/auth/csrf` 조회 절차를 명시한다. Swagger Try it out에 필요한 헤더 입력도 제공한다.
- `GlobalExceptionHandler`와 Spring Security 필터의 응답은 다를 수 있다. 401/403/비즈니스 오류를 모두 `ApiResponse`로 가정하지 말고 실제 상태 코드와 본문을 확인한다.
- 예시는 가상 이메일·식별자·내용으로 작성한다. 실제 JWT·API 키·개인정보를 넣지 않는다. 예시를 맞추기 위해 API 동작이나 DTO 계약을 변경하지 않는다.

## 검증과 완료 보고

변경 범위의 매핑을 확인하고 설명·파라미터·요청 예시·성공 및 실패 응답 예시의 누락을 검토한다. 기존 springdoc 의존성을 사용하며 문서화만을 위해 라이브러리를 추가하지 않는다.

Java 어노테이션 변경 후 컴파일하고, 제네릭 schema·보안·응답 예시를 변경하면 test 프로필과 외부 AI 모델 모킹을 사용한 기존 테스트 구성을 참고해 `/v3/api-docs` 생성 결과를 검증한다. 실제 HTTP 메서드/경로, 상태 코드, media type, 필수 파라미터, schema와 examples를 확인한다. 공유 DTO 변경은 이를 사용하는 다른 API 문서도 확인한다.

완료 시 문서화한 범위, 검증 결과와 남은 누락을 정확히 보고한다. Swagger UI 실제 확인을 수행하지 않았다면 수행했다고 보고하지 않는다.
