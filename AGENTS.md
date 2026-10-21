# Repository Guidelines

## 실험 관리 스킬

Qdrant 실험 컬렉션·별칭·벡터 데이터 정리에는 프로젝트 스킬 [qdrant-experiment-management](.agents/skills/qdrant-experiment-management/SKILL.md)를 읽고 적용하세요. 실험 파일·보고서·입력 자료 재배치에는 [experiment-file-organization](.agents/skills/experiment-file-organization/SKILL.md)를 읽고 적용하세요. 두 작업이 함께 있으면 두 스킬을 모두 사용하며, 기존 고정 데이터와 실험 결과를 보존하세요.

## 프로젝트 구조와 모듈 구성
MoleLaw는 법률 검색과 AI 채팅을 제공하는 Java 17 / Spring Boot 3.5 백엔드입니다. 소스는 `src/main/java/com/MoleLaw_backend/`에 있습니다.

- `auth`: JWT·OAuth·CSRF·인증 저장소와 보안 설정
- `user`: 사용자 엔티티·저장소와 계정·프로필 관리 API
- `chat`: 상담방·메시지·상담 흐름과 메시지 암호화
- `law`: 법령·판례·원문·검색·임베딩·법령 도구와 Qdrant 설정
- `common`: 공통 응답·예외, DB·Swagger 설정과 공통 AI 호출

각 도메인 내부에 필요한 `controller`, `service`, `entity`, `repository`, `dto/request`, `dto/response` 등을 둡니다. 최상위 기술 계층 패키지를 새로 만들지 마세요. Java 기능 추가·패키지 이동·구조 리뷰 시 프로젝트 스킬 [domain-structure](.agents/skills/domain-structure/SKILL.md)를 읽고 적용하세요. 테스트 패키지는 구현 패키지를 따르며 여러 도메인의 설정 통합 테스트는 `common.config`에 둡니다.

설정 파일은 `src/main/resources`, 테스트는 `src/test/java`, 설계·실행 문서는 [문서 안내](docs/README.md)를 참고하세요. 빌드 결과는 `build/`에 생성됩니다. 배포 설정은 `docker/app.Dockerfile`과 `.github/workflows/deploy.yml`을 확인하세요.

## 빌드·테스트·로컬 실행
JDK 17과 저장소에 포함된 Gradle Wrapper를 사용하세요. Windows 기준 명령어는 다음과 같습니다.

- `.\gradlew.bat clean build`: 기존 빌드 결과를 정리하고 컴파일, 테스트, 패키징 수행
- `.\gradlew.bat test`: 전체 JUnit 테스트 실행
- `.\gradlew.bat test --tests com.MoleLaw_backend.LawMateBackendApplicationTests`: 기존 컨텍스트 테스트 실행
- `.\gradlew.bat bootRun`: 로컬 설정으로 서버 실행
- `.\gradlew.bat bootJar`: `build/libs/`에 실행 가능한 JAR 생성

Unix에서는 `./gradlew`를 사용하세요. 실행 전에 MySQL 연결과 환경변수를 설정하세요. API 문서는 `/swagger-ui.html`에서 확인할 수 있습니다.

기본 프로필은 `mysql`이며 MySQL `DataSource`가 `@Primary`입니다. `local` 프로필은 `mysql`과 `qdrant`를 함께 활성화합니다. 실행 설정은 루트 `.env.example`, DB 컨테이너 설정은 `docker/.env.compose.example`과 `docker/README.md`를 참고하세요. Qdrant는 JPA 데이터소스가 아닌 별도 `VectorStore`입니다.

## 코드 스타일과 이름 규칙
새 Java 코드는 공백 4칸으로 들여쓰기하고, 기존 파일 수정 시 주변 스타일을 따르세요. 클래스는 PascalCase, 메서드와 필드는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용하세요. 패키지 루트는 현재 `com.MoleLaw_backend`입니다.

역할에 따라 `Controller`, `Service`, `Repository`, `Request`, `Response` 접미사를 사용하세요. 의존성은 생성자로 주입하고 기존 Lombok `@RequiredArgsConstructor` 패턴을 참고하세요. 현재 자동 포매터나 린트 플러그인은 설정되어 있지 않습니다.

## Swagger API 문서화
Swagger UI·OpenAPI 문서 노출과 Swagger용 CSRF 자동 처리는 개발용 `local` 프로필로 한정합니다. 공통 설정과 `main` 배포 기본값은 `springdoc.api-docs.enabled=false`, `springdoc.swagger-ui.enabled=false`를 유지하세요. `test`에서만 문서 검증용 활성화를 허용하며 운영에 `local`·`test` 프로필을 사용하지 마세요. Swagger 편의를 위해 운영의 인증·CORS·CSRF·쿠키 정책을 완화하지 마세요. 운영 CSRF 쿠키는 HttpOnly를 유지하고 프론트는 `/api/auth/csrf` JSON의 토큰을 헤더로 전송합니다. 컨트롤러 문서 어노테이션은 모든 환경의 소스에 유지합니다.
모든 HTTP 컨트롤러는 Swagger 설명과 요청·응답 샘플을 제공해야 합니다. 컨트롤러·API DTO를 추가하거나 수정할 때 프로젝트 스킬 [swagger-controller-docs](.agents/skills/swagger-controller-docs/SKILL.md)를 읽고 적용하세요. 엔드포인트 목적, 파라미터, 실제 성공·실패 응답, 인증·CSRF 요구사항을 코드와 함께 갱신하세요. 요청이나 응답 본문이 없는 API는 해당 사실과 실제 헤더·상태 코드를 문서화하세요.

## 테스트 작성 기준
JUnit Jupiter, Spring Boot Test, Mockito, Spring Security Test를 사용합니다. 테스트 클래스 이름은 `*Tests` 또는 `*Test`로 작성하고 메서드 이름에 검증할 동작을 드러내세요. 변경한 기능의 정상 동작, 권한 검사, 실패 경로를 검증하세요. 외부 OpenAI/OpenLaw 호출은 모킹하세요.

컨텍스트 테스트는 `test` 프로필의 H2와 더미 인증 설정을 사용하고 `ChatModel`·`EmbeddingModel`을 모킹합니다. Docker·실제 API 키 없이 전체 테스트를 실행할 수 있습니다. `test`가 활성화되면 MySQL·Qdrant 설정은 적용하지 않습니다. H2 테스트는 실제 MySQL·Qdrant 통합 검증을 대체하지 않습니다. 현재 커버리지 기준은 없습니다.

## 커밋과 PR 작성 기준
기존 이력의 `fix:`, `refactor:`, `docs:`, `readme:` 접두사와 간결한 한글 설명을 따르세요. PR에는 변경 목적, 변경된 동작, 관련 이슈와 검증 결과를 포함하세요. API 변경 시 요청·응답 예시를 제공하고 관련 문서를 갱신하세요.

## 보안과 환경 설정
비밀값은 환경변수 또는 Git에서 제외된 `.env`로 관리하세요. 실행에 필요한 OAuth 인증 정보, `OPENAI_API_KEY`, `OC_KEY`, `FRONTENDURI`, `COOKIE_SECURE`를 설정하세요. 저장소의 DB 및 JWT 설정은 로컬 값으로 덮어쓰고, 자동 스키마 변경은 개발용 DB에만 적용하세요. 비밀값을 커밋하거나 로그에 출력하지 마세요.
