# 폴더 구조

기준: 2026-11-11. Java 소스와 테스트의 7개 계층을 유지하고, Spring AI 실행 로직은 service.agents, ChatClient 설정은 config.agents로 모았다. Java 클래스·package·실제 경로는 일치해야 한다.

```text
src/main/java/com/MoleLaw_backend/
  MoleLawBackendApplication.java
  controller/             HTTP API·오류 응답·전역 핸들러
  service/                사용자·상담·법령 기능
    agents/               Spring AI 기반 상담·검색 실행
      ai/                 공통 AI 호출·프롬프트 제공
      answer/             상담 답변·스트림
      retrieval/          근거 수집·판단
      tool/               법령·판례 실행 도구
      memory/             영속 대화 메모리
      analysis/           AI 키워드 추출
    client/openlaw/       OpenLaw HTTP
    client/vector/        Qdrant·임베딩·리랭커 HTTP
    security/             인증·토큰 기능
    oauth/                OAuth 사용자 조회
    ...                   etl/evidence/search/source
  config/                 Spring·DB·Swagger·벡터 설정
    agents/               ChatClient·모델 추론 옵션 설정
    security/             인증 필터·OAuth 성공 핸들러
  dto/                    request/response/answer/evidence/retrieval/tool/vector/security
  dao/                    JPA Repository·Refresh 저장소
  entity/                 JPA 엔티티
  util/                   파싱·암호화·JWT·쿠키 보조 처리
    exception/            오류 코드·예외 타입
    validation/           입력 검증 애너테이션·validator
src/main/resources/       프로필·프롬프트·Flyway·Refresh Lua
src/test/java/            구현과 같은 계층/패키지
src/test/resources/       테스트 원문 fixture
```

기존 Repository 클래스명과 Spring Data 계약은 유지하고 패키지만 dao로 옮겼다. Redis 저장 인터페이스와 구현도 dao다. GlobalExceptionHandler는 controller, ErrorResponse와 ApiResponse는 dto.response에 있다.

JPA 엔티티 이름·테이블·API 경로·요청/응답 JSON·쿠키·보안 정책은 이 구조 이동에서 변경하지 않는다. src/main/resources/auth의 Lua는 Java 패키지와 무관한 실행 리소스이므로 유지한다.

저장소 최상위의 docs·docker·postman·scripts·.agents/skills·.github/workflows·gradle은 그대로 사용한다. build와 __pycache__ 등 생성 결과는 추적하지 않는다. 상세 배치 규칙은 [구조 스킬](../../.agents/skills/domain-structure/SKILL.md), 오류 처리 규칙은 [오류 처리 스킬](../../.agents/skills/controller-error-handling/SKILL.md)을 따른다.
