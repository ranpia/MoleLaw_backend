# Repository Guidelines

## 프로젝트 구조와 모듈 구성
MoleLaw는 법률 검색과 AI 채팅을 제공하는 Java 17 / Spring Boot 3.5 백엔드입니다. 소스는 `src/main/java/com/MoleLaw_backend/`에 있습니다.

- `controller`: HTTP 요청과 응답 처리
- `service`: 채팅, 법률 검색, 사용자, OAuth 및 인증 로직
- `domain/entity`, `domain/repository`: JPA 엔티티와 데이터 접근
- `dto/request`, `dto/response`: API 요청 및 응답 모델
- `config`, `exception`, `util`: 설정, 공통 예외 처리 및 유틸리티

설정 파일은 `src/main/resources`, 테스트는 `src/test/java`, 구조 설명 이미지는 `docs/`에 있습니다. 빌드 결과는 `build/`에 생성됩니다. 배포 설정은 `Dockerfile`과 `.github/workflows/deploy.yml`을 확인하세요.

## 빌드·테스트·로컬 실행
JDK 17과 저장소에 포함된 Gradle Wrapper를 사용하세요. Windows 기준 명령어는 다음과 같습니다.

- `.\gradlew.bat clean build`: 기존 빌드 결과를 정리하고 컴파일, 테스트, 패키징 수행
- `.\gradlew.bat test`: 전체 JUnit 테스트 실행
- `.\gradlew.bat test --tests com.MoleLaw_backend.LawMateBackendApplicationTests`: 기존 컨텍스트 테스트 실행
- `.\gradlew.bat bootRun`: 로컬 설정으로 서버 실행
- `.\gradlew.bat bootJar`: `build/libs/`에 실행 가능한 JAR 생성

Unix에서는 `./gradlew`를 사용하세요. 실행 전에 MySQL 연결과 환경변수를 설정하세요. API 문서는 `/swagger-ui.html`에서 확인할 수 있습니다.

## 코드 스타일과 이름 규칙
새 Java 코드는 공백 4칸으로 들여쓰기하고, 기존 파일 수정 시 주변 스타일을 따르세요. 클래스는 PascalCase, 메서드와 필드는 camelCase, 상수는 UPPER_SNAKE_CASE를 사용하세요. 패키지 루트는 현재 `com.MoleLaw_backend`입니다.

역할에 따라 `Controller`, `Service`, `Repository`, `Request`, `Response` 접미사를 사용하세요. 의존성은 생성자로 주입하고 기존 Lombok `@RequiredArgsConstructor` 패턴을 참고하세요. 현재 자동 포매터나 린트 플러그인은 설정되어 있지 않습니다.

## 테스트 작성 기준
JUnit Jupiter, Spring Boot Test, Mockito, Spring Security Test를 사용합니다. 테스트 클래스 이름은 `*Tests` 또는 `*Test`로 작성하고 메서드 이름에 검증할 동작을 드러내세요. 변경한 기능의 정상 동작, 권한 검사, 실패 경로를 검증하세요. 외부 OpenAI/OpenLaw 호출은 모킹하세요.

기존 컨텍스트 테스트는 `test` 프로필을 사용합니다. 해당 프로필은 더미 API 키만 제공하므로 별도의 테스트 DB 설정이 필요합니다. 현재 커버리지 기준은 없습니다.

## 커밋과 PR 작성 기준
기존 이력의 `fix:`, `refactor:`, `docs:`, `readme:` 접두사와 간결한 한글 설명을 따르세요. PR에는 변경 목적, 변경된 동작, 관련 이슈와 검증 결과를 포함하세요. API 변경 시 요청·응답 예시를 제공하고 관련 문서를 갱신하세요.

## 보안과 환경 설정
비밀값은 환경변수 또는 Git에서 제외된 `.env`로 관리하세요. 실행에 필요한 OAuth 인증 정보, `OPENAI_API_KEY`, `OC_KEY`, `FRONTENDURI`, `COOKIE_SECURE`를 설정하세요. 저장소의 DB 및 JWT 설정은 로컬 값으로 덮어쓰고, 자동 스키마 변경은 개발용 DB에만 적용하세요. 비밀값을 커밋하거나 로그에 출력하지 마세요.
