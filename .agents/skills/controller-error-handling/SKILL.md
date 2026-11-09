---
name: controller-error-handling
description: MoleLaw의 서비스·컨트롤러·보안·스트림 예외 처리를 추가하거나 수정할 때 오류 응답과 사용자 메시지를 컨트롤러 계층에 두고 Slf4j 로깅 규칙을 적용한다.
---

# 컨트롤러 중심 오류 처리

서비스는 결과나 예외를 전달하고, 컨트롤러는 사용자에게 보여 줄 오류 메시지·상태·본문을 생성한다. 기존 서비스 전체를 컨트롤러로 옮기는 규칙이 아니라 **오류 응답 책임**을 옮기는 규칙이다.

## 책임과 배치

- 도메인 오류는 `controller`의 해당 API 컨트롤러 `@ExceptionHandler`에서 처리한다. `util.exception.MolelawException`은 ErrorCode·선택적 상세 안내·원인을 운반하는 최소 예외로 유지한다. 컨트롤러에서 `dto.response.ApiResponse.error(new ErrorResponse(...))`와 JSON Content-Type·상태 코드를 설정한다.
- 공통 입력 검증·바인딩·404·405·415와 미처리 오류는 `controller.GlobalExceptionHandler`의 `@RestControllerAdvice`가 처리한다. 컨트롤러 핸들러가 먼저 실행되며 전역 핸들러가 잡은 예외를 다시 던져 컨트롤러로 돌려보내는 구조는 만들지 않는다.
- GlobalExceptionHandler는 공통 분류·오류 로그·필터 연결도 제공한다. 인증·인가·CSRF·CORS·Origin·OAuth처럼 MVC 밖에서 발생한 실패는 이 경계로 전달한다. 이미 전송한 HTTP 상태·본문을 덮어쓰지 않는다.
- SSE/NDJSON은 전송 시작 후의 실패를 공통 분류에 연결한 기존 error 이벤트로 전달한다. 새 JSON 응답을 덧붙이거나 완료·오류 이벤트를 중복 전송하지 않는다.

## 서비스와 예외

- 서비스·저장소·유틸에서 ResponseEntity, HttpServletResponse, ApiResponse.error, sendError 등으로 HTTP 오류 응답을 생성하지 않는다. HTTP 코드나 사용자 안내로 포장하기 위한 포괄적 `catch (Exception/RuntimeException)`도 추가하지 않는다. 원래 예외를 컨트롤러로 전파한다.
- 업무 조건 위반을 발견한 서비스가 기존 도메인 예외를 던지는 것은 허용한다. JSON/암호화 등 checked exception의 도메인 변환, 외부 클라이언트의 비밀값 제거, 제한된 재시도·fallback·부분 결과 판단, 취소 전달·자원/색인 상태 정리 같은 **실제 실행 복구**에 필요한 catch는 실행 위치에 유지한다. 이를 일괄 삭제해 트랜잭션·재시도·실패 닫힘 동작을 바꾸지 않는다.
- 외부 통신 장애를 단순히 HTTP 오류로 바꾸는 서비스 wrapper는 제거하고 해당 컨트롤러의 좁은 예외 핸들러로 옮긴다. IllegalArgumentException 전부를 전역 400으로 취급하지 말고 입력 경계가 명확한 컨트롤러에서 처리한다. 내부 모델 변환 오류를 사용자 입력 오류로 오인하지 않는다.
- 새 예외 타입·중간 계층·중복 메시지 필드는 실제 분기 필요가 있을 때만 만든다. 기존 원인과 도메인 오류 코드를 보존하고 저장 실패를 무조건 GPT 오류로 바꾸지 않는다.

## 로그와 검증

- Java 애플리케이션 로그는 Lombok `@Slf4j`의 log를 사용한다. System.out/err, printStackTrace, 직접 LoggerFactory 생성을 사용하지 않는다.
- 컨트롤러 오류 로그는 GlobalExceptionHandler.logFailure에 연결해 4xx WARN·5xx ERROR로 한 번 기록한다. 실행 복구의 진단 로그는 도메인에서 유지할 수 있다. 예외 메시지·외부 URL/응답·키·토큰·질문 본문을 그대로 로깅하지 않는다.
- 변경 후 실제 HTTP 정상·도메인 오류·미처리 오류, 인증/CSRF/CORS, 스트림 오류와 저장 여부를 해당 범위에 맞게 검증한다. 서비스 테스트는 예외 전파·복구를, 컨트롤러 테스트는 상태·메시지·Content-Type·민감값 제외를 확인한다.
- 오류 계약이 바뀌면 [swagger-controller-docs](../swagger-controller-docs/SKILL.md)를 적용해 코드 예시와 실제 OpenAPI를 맞춘다. 구조 변경은 [domain-structure](../domain-structure/SKILL.md)를 따른다. Spring 기본 Allow 등 오류 헤더와 기존 보안 정책을 유지한다.
