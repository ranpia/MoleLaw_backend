---
name: domain-structure
description: MoleLaw 백엔드의 기능 추가, 클래스 배치, 패키지 이동과 구조 리뷰 시 도메인별 패키지 경계를 적용한다.
---

# MoleLaw 도메인 구조

패키지 루트는 `com.MoleLaw_backend`다. 최상위는 기술 계층이 아닌 업무 소유권으로 정한다.

| 패키지 | 소유하는 책임 |
| --- | --- |
| `auth` | JWT, Access 저장소, OAuth, CSRF, 보안 필터와 보안 설정, 로그인·가입·인증 DTO |
| `user` | 사용자 엔티티·저장소, 계정과 프로필 관리, 사용자 HTTP API |
| `chat` | 상담방·메시지, 상담 흐름 조정, 후속 답변, 메시지 암호화와 답변 DTO |
| `law` | 법령·판례·원문, 검색·임베딩·법령 도구, 법령 파싱, Qdrant 설정 |
| `common` | 업무에 독립적인 공통 응답·예외, DB·Swagger 설정, 공통 AI 호출 |

## 배치 기준

- 각 도메인 아래 `controller`, `service`, `entity`, `repository`, `dto/request`, `dto/response`, `config`, `exception`, `util` 중 실제 필요한 패키지만 만든다. 최상위 `controller`, `service`, `domain/entity`, `dto`, `util`은 다시 만들지 않는다.
- 인증 구현은 `auth.security`와 `auth.oauth`, 법령 도구는 `law.service.tool`, 공통 AI 호출과 프롬프트는 `common.ai`에 둔다. 업무 전용 AI 흐름은 해당 도메인에 둔다.
- 공통 API 래퍼는 `common.dto.response.ApiResponse`다. 특정 도메인 타입을 공통으로 옮겨 의존성을 숨기지 않는다. `common`은 업무 도메인에 의존하지 않는 것을 원칙으로 한다.
- 도메인 간 호출은 가능한 서비스의 공개 메서드로 연결한다. 현재 `chat`의 `law` 서비스 호출, `auth`의 사용자 조회, `ChatRoom`↔`User` JPA 연관관계는 허용된 기존 연결이다. 패키지 정리만을 위해 저장 모델이나 API 계약을 바꾸지 않는다.
- 현재 `UserController`는 로그인·가입 API도 함께 가진다. 해당 클래스는 `user.controller`에 유지하고 인증 DTO는 `auth`가 소유한다. `TestController`는 법령 기능을 제공하므로 `law.controller`에 둔다. 새 기능은 이름 대신 책임으로 배치한다.

## 변경과 검증

대상 클래스의 호출자와 테스트를 확인하고 소유 도메인을 정한다. 이동 시 package, import, 완전한 클래스명 문자열과 설정의 스캔 경로를 함께 갱신한다. 같은 패키지여서 생략되었던 import도 확인하고 wildcard import는 필요한 클래스의 명시적 import로 정리한다.

테스트는 `src/test/java`에서 대상 구현의 패키지를 따른다. 여러 도메인의 통합 설정 검증은 `common.config`, 애플리케이션 컨텍스트 테스트는 루트에 둔다. 리소스 경로는 Java 패키지에 맞추기 위해 불필요하게 옮기지 않는다.

컨트롤러·API DTO 변경에는 저장소의 [swagger-controller-docs](../swagger-controller-docs/SKILL.md)를 함께 적용한다. 구조 변경 후 Gradle 전체 테스트로 JPA·컴포넌트 검색, 보안과 OpenAPI 회귀를 확인한다. 새로운 업무 도메인이 필요하면 소유 책임을 설명하고 이 표와 루트 `AGENTS.md`, `README.md`를 함께 갱신한다.
