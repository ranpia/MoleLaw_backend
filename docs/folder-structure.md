# 폴더 구조 검토

기준: 2026-10-22, dev. Java package와 실제 경로를 전수 비교했으며 불일치는 없다. 테스트는 구현 패키지를 따르고 공통 설정·다중 도메인 통합 검증은 common/config에 둔다.

```text
src/
  main/java/com/MoleLaw_backend/
    auth/       인증·OAuth·토큰·CSRF
    user/       계정·프로필·사용자 저장
    chat/       상담방·메시지·답변·근거·메모리
    law/        법령·판례·수집·검색·색인
    common/     공통 응답·예외·AI 호출·설정
  main/resources/
    application.yml       공통 설정
    application-*.yml     프로필별 설정
    auth/                 Redis Refresh Lua
    db/migration/         실행하는 Flyway SQL
    prompts/              모델 역할별 프롬프트
  test/java/              구현과 같은 패키지
  test/resources/         테스트용 원문 fixture
```

## 도메인 내부 기준

controller는 HTTP, dto는 전달 계약, service는 기능 실행·흐름 조정, entity/repository는 저장, client는 외부 HTTP, util은 외부 I/O·트랜잭션 없는 보조 처리다. law/util에 파싱·부처 매핑·청킹·로컬 부하 제한을 모았다. service/vector처럼 비게 된 중간 폴더는 제거했다.

기능을 찾는 데 필요한 service 하위 폴더와 HTTP/내부 계약을 구분하는 dto 하위 폴더는 유지한다. 모든 폴더의 깊이를 같게 만들기 위한 폴더나, 빈 controller/service/repository 뼈대는 추가하지 않는다. 현재 테스트가 없는 구현 폴더에 빈 테스트 폴더를 만들지 않는다.

## 저장소 최상위

| 폴더 | 용도 |
| --- | --- |
| docs | 현재 실행·구현 문서. plans는 목표, archive는 과거 참고 자료 |
| docker | 컨테이너·로컬 임베딩 실행 설정 |
| postman | HTTP 대역과 simulation 계약 |
| .agents/skills | 저장소 개발 기준 |
| .github/workflows | CI·수동 배포 |
| gradle | Gradle Wrapper |
| evaluation | 별도 검색·모델 실험 자료. 서비스 구현과 검증 완료를 혼동하지 않음 |
| build/.gradle/.idea/.codex | 생성물·로컬 도구 상태 |

## 이번 정리

- 기본 설정의 application.properties를 application.yml로 통합했다. 애플리케이션 이름과 springdoc 옵션을 보존하고 중복 Swagger 경로를 제거했다.
- 비어 있던 docs/archive에 과거 수동 SQL을 모았다. docs/sql은 제거하고 기존 링크를 갱신했다.
- 현재 실행 SQL은 db/migration에만 둔다. 과거 SQL과 새 migration을 중복 실행하지 않는다.

## 별도 관리가 필요한 항목

저장소 루트의 bfg-1.15.0.jar와 secrets.txt는 Git 추적 중인 이력 정리용 관리 파일이며 애플리케이션 소스가 아니다. 내용을 출력하지 않고 파일 경로·추적 상태만 확인했다. 특히 secrets.txt는 실제 비밀값 포함 여부를 별도로 확인하고 추적 제외 여부를 결정해야 한다. 해당 두 관리 파일과 Git 인덱스는 이번 폴더 정리에서 변경하지 않았다.

세부 소유권·DTO·API 경로는 [구조·API 정리](package-api-organization.md)를 따른다.
검증: 설정 통합 후 일반 테스트 283개(실패 0·선택 실행 1개 건너뜀)와 bootJar를 통과했다. SwaggerDisabledTest가 통합 YAML의 기존 옵션 보존과 운영 문서 비활성화를 확인한다.
