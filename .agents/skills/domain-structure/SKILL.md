---
name: domain-structure
description: MoleLaw의 기능 추가·패키지 이동·구조 리뷰 시 controller/service/config/dto/dao/entity/util 계층 배치와 의존성을 적용한다.
---

# MoleLaw 계층별 구조

패키지 루트는 `com.MoleLaw_backend`다. 2026-11-09 사용자 결정으로 이전 auth/user/chat/law/common 도메인 우선 구조를 아래 7개 계층으로 통합했다. skill 이름은 기존 참조 호환을 위해 유지한다.

| 패키지 | 책임 |
| --- | --- |
| controller | HTTP API·도메인 오류 메시지/응답·GlobalExceptionHandler |
| service | 기능 실행·흐름 조정·AI 호출·외부 클라이언트 |
| config | Spring·DB·보안·Swagger·Qdrant 설정, 인증 필터·OAuth 성공 핸들러 |
| dto | HTTP 요청/응답과 내부 근거·검색·도구·벡터·인증 주체 계약 |
| dao | JPA Repository·Refresh 저장 인터페이스·Redis 저장 구현 |
| entity | JPA 엔티티 |
| util | 순수 파싱·암호화·JWT/쿠키 보조 처리·검증과 예외 타입 |

## 배치

- 위 계층을 최상위로 사용하고 auth/user/chat/law/common 또는 repository/client/exception 최상위 폴더를 다시 만들지 않는다. 애플리케이션 진입 클래스는 루트에 유지한다.
- 필요한 역할별 하위 패키지만 사용한다. 상담 답변은 service.answer, 근거는 service.evidence, 메모리는 service.memory, 법령 수집/검색/도구는 service.retrieval/search/tool/source/etl/analysis, 외부 HTTP는 service.client.openlaw/vector에 둔다. 공통 AI 호출은 service.ai다.
- 인증 기능은 service.security/oauth, 필터와 OAuth 성공 핸들러는 config.security, 토큰 저장소는 dao, CustomUserDetails는 dto.security에 둔다.
- HTTP 계약은 dto.request/response, 공유 내부 계약은 dto.answer/evidence/retrieval/tool/vector, 공통 API 래퍼와 오류 응답은 dto.response에 둔다. 공유 타입을 컨트롤러·서비스 중첩 타입으로 숨기지 않는다. 작은 도구 계약을 한 파일에 묶는 것은 허용한다.
- util에는 저장소·HTTP 클라이언트 의존성을 넣지 않는다. 실행·트랜잭션 조정은 service가 맡고, 검증 애너테이션은 util.validation, 예외/오류 코드는 util.exception에 둔다. 오류 응답 DTO는 util.exception에 두지 않는다.
- 오류 응답은 controller-error-handling 스킬을 적용한다. 컨트롤러는 서비스의 공개 메서드로 기능을 호출하고 dao·외부 HTTP 구현을 직접 호출하지 않는다. 구조만 정리할 때는 API 경로·JSON·엔티티 이름·테이블·보안 정책을 바꾸지 않는다.
- 설정은 Bean 연결과 정책별 설정을 맡는다. 재사용되는 정책/프로토콜 구현·큰 실행 세션·다른 클래스가 공유하는 반환 계약을 설정/서비스의 익명 또는 중첩 클래스로 숨기지 않는다. 반대로 소유 객체의 수명에 결합된 permit/session 핸들·구현 전용 private 상태·짧은 콜백·한 DTO의 enum은 무조건 분리하지 않는다. 현재 분리 기준은 docs/architecture/class-responsibilities.md를 참고한다.

## 이동과 검증

- 대상 클래스와 호출자·같은 패키지에서 생략했던 import를 확인한다. package·명시적/정적 import·완전한 클래스명·스캔/로거 설정·Gradle 테스트 필터·스크립트·실행 문서의 참조를 함께 갱신한다.
- 테스트는 src/test/java에서 구현 패키지를 따르고 공통 설정 통합 테스트는 config, 애플리케이션 컨텍스트 테스트는 루트에 둔다. 리소스·Flyway SQL·Lua·프롬프트·문서를 Java 패키지에 맞추기 위해 옮기지 않는다.
- 구조 변경 후 전체 Gradle 테스트로 JPA·컴포넌트 검색·보안·OpenAPI·스트림 회귀를 확인한다. 전용 simulation/integration 태스크의 테스트 선택도 확인한다. 관련 구조 문서·AGENTS·README·스킬을 함께 맞춘다.
- 오류 처리 변경에는 [controller-error-handling](../controller-error-handling/SKILL.md), API/DTO 변경에는 [swagger-controller-docs](../swagger-controller-docs/SKILL.md)를 적용한다.
