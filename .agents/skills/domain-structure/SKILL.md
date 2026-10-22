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
- 인증 구현은 `auth.security`와 `auth.oauth`에 둔다. 법령 실행 도구·권한 컨텍스트는 `law.service.tool`, 원문 저장은 `law.service.source`, 수집 판단은 `law.service.retrieval`, 파싱·청킹·코드 매핑·부하 제한 보조 코드는 `law.util`, 외부 HTTP 경계는 `law.client.openlaw`와 `law.client.vector`에 둔다. 공통 AI 호출은 `common.ai`에 유지한다.
- `law.service`에는 유스케이스·외부 호출·저장 흐름 조정을 두고, 외부 I/O·트랜잭션 없이 동작하는 파싱·변환·분할·로컬 동시성 제어는 `law.util`에 둔다. util에 저장소·HTTP 클라이언트 의존성을 넣거나 같은 성격의 보조 클래스를 service에 다시 섞지 않는다. 클래스별 일회성 폴더를 만들지 않는다.
- HTTP DTO는 도메인의 `dto.request/response`, 내부 근거·판단·도구·벡터 계약은 `law.dto.evidence/retrieval/tool/vector`, 답변 준비 결과는 `chat.dto.answer`에 둔다. 여러 서비스가 공유하는 데이터 타입을 서비스 중첩 record나 컨트롤러 안에 두지 않는다. 같은 기능의 작은 도구 계약은 LegalToolContracts처럼 한 파일에 묶어도 된다.
- 공통 API 래퍼는 `common.dto.response.ApiResponse`다. 특정 도메인 타입을 공통으로 옮겨 의존성을 숨기지 않는다. `common`은 업무 도메인에 의존하지 않는 것을 원칙으로 한다.
- 도메인 간 호출은 가능한 서비스의 공개 메서드로 연결한다. 현재 `chat`의 `law` 서비스 호출, `auth`의 사용자 조회, `ChatRoom`↔`User` JPA 연관관계는 허용된 기존 연결이다. 패키지 정리만을 위해 저장 모델이나 API 계약을 바꾸지 않는다.
- 인증 API는 `auth.controller.AuthController`, 사용자 프로필 API는 `user.controller.UserController`가 소유한다. 법령·판례·키워드는 `law.controller`의 각 컨트롤러, 무저장 법률 답변과 상담방은 `chat.controller`가 소유한다. `/api/test` 또는 기능을 섞은 TestController를 다시 만들지 않는다.

## 변경과 검증

대상 클래스의 호출자와 테스트를 확인하고 소유 도메인을 정한다. 이동 시 package, import, 완전한 클래스명 문자열과 설정의 스캔 경로를 함께 갱신한다. 같은 패키지여서 생략되었던 import도 확인하고 wildcard import는 필요한 클래스의 명시적 import로 정리한다.

테스트는 `src/test/java`에서 대상 구현의 패키지를 따른다. 여러 도메인의 통합 설정 검증은 `common.config`, 애플리케이션 컨텍스트 테스트는 루트에 둔다. 리소스 경로는 Java 패키지에 맞추기 위해 불필요하게 옮기지 않는다. 기본 설정은 application.yml, 프로필 설정은 application-*.yml에 두고 application.properties와 중복 관리하지 않는다. 실행 스키마는 src/main/resources/db/migration, 폐기된 수동 SQL은 docs/archive/sql에 둔다.

컨트롤러·API DTO 변경에는 저장소의 [swagger-controller-docs](../swagger-controller-docs/SKILL.md)를 함께 적용한다. 구조 변경 후 Gradle 전체 테스트로 JPA·컴포넌트 검색, 보안과 OpenAPI 회귀를 확인한다. 새로운 업무 도메인이 필요하면 소유 책임을 설명하고 이 표와 루트 `AGENTS.md`, `README.md`를 함께 갱신한다.
