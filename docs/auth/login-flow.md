# 로그인 흐름

현재 코드 기준이다. 이메일 로그인과 소셜 로그인을 나누고, 공통 토큰 발급은 별도 그림으로 표시한다. 각 단계의 검증·저장소 오류는 로그인 실패로 종료한다.

## 이메일 로그인

```mermaid
flowchart LR
    A[이메일·비밀번호] --> B[CSRF·입력 검증]
    B --> C{로컬 계정 존재?}
    C -->|아니요| X[로그인 실패]
    C -->|예| D{비밀번호 일치?}
    D -->|아니요| X
    D -->|예| E[[토큰 발급]]
    E --> F[로그인 응답]
```

- `POST /api/auth/login`은 이메일·`local` provider로 계정을 조회하고 잠근다. 비밀번호는 저장 해시와 비교하며 공백을 제거하지 않는다.
- CSRF는 먼저 `GET /api/auth/csrf`로 발급받고 쿠키와 `X-XSRF-TOKEN` 헤더를 함께 보낸다.
- 성공은 HTTP 200이다. Access는 JSON과 쿠키에, Refresh는 HttpOnly 쿠키에만 전달한다. 이메일이 같아도 소셜 계정과 자동 연결하지 않는다.

## 소셜 로그인

```mermaid
flowchart LR
    A[소셜 로그인 시작] --> B[제공자 인증]
    B --> C[제공자 식별자 확인]
    C --> D[계정 조회·생성]
    D --> E[계정 잠금·식별자 재검증]
    E --> F[[토큰 발급]]
    F --> G[프론트로 이동]
```

등록된 OAuth 제공자를 사용한다. 계정은 provider·subject로 식별하며 비밀번호를 만들지 않는다. 콜백 검증 실패나 토큰 발급 실패는 프론트 성공 이동으로 처리하지 않는다.

## 공통 토큰 발급

```mermaid
flowchart LR
    A[사용자 ID] --> B[Access JWT 생성]
    B --> C[Refresh 생성·Redis 저장]
    C --> D{저장 성공?}
    D -->|아니요| X[로그인 실패]
    D -->|예| E[인증 쿠키 설정]
    E --> F[호출 경로로 복귀]
```

Access는 최대 5분이며 요청마다 Redis에서 조회하지 않는다. Refresh 상태는 Redis에서 회전·재사용 탐지·폐기를 관리한다. 토큰 값은 URL에 넣지 않는다. 자세한 정책은 [Access](stateless-access.md), [Refresh](refresh-rotation.md)를 따른다.

[기능·질문 처리 흐름](../project/features-and-question-flow.md) · [인증 문서](README.md)
