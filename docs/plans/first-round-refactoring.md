# 1차 테스트 기반 개선 및 리팩토링

[1차 테스트](../testing/first-round.md)의 결과와 현재 소스를 함께 검토한다. 반복 코드 제거는 동작·권한·실행 한도·동적 스키마를 보존하는 범위에서 진행한다. 성능 변화는 후속 재측정으로 확인한다.

## 우선 변경

| 항목 | 확인한 문제 | 변경 방향 | 검증 |
| --- | --- | --- | --- |
| 후속 계획 설정 | YAML은 spring.ai.follow-up-plan, Bean은 app.ai.follow-up-plan | Bean이 읽는 app.ai 아래에 환경변수 매핑 통일 | 실제 YAML·비기본 환경변수로 모델/effort/완료 한도·다른 역할 격리 확인 |
| 구조화 모델 옵션 | 요청마다 schema-only 옵션을 만들면 ChatClient의 역할 옵션 전체가 교체됨 | 역할 옵션을 한 곳에서 정의하고 요청별 복사본에 동적 schema 결합 | 읽기·판례 질의·후보 리랭크·후속 계획의 옵션과 ID enum·동적 범위 보존 |
| 방 목록 N+1 | 페이지의 방마다 첫 메시지 SQL, 총건수 미사용 Page | 페이지 방 조회 후 첫 메시지 일괄 조회, count 없는 목록 조회 | 방 수와 독립적인 SQL 수, 시간/ID 정렬·빈 방·복호화·타인 방 격리 |
| 빌드 중복 | WebFlux starter가 dependencies에 두 번 선언됨 | 중복 선언 제거 | 전체 컴파일·회귀 |

## 보일러플레이트·로직 검토

Java 소스의 앞뒤 공백을 제거한 연속 6줄을 파일 간 비교했다. 120자 미만 및 import/package/주석 포함 블록은 제외했다. 겹치는 창을 포함해 반복 후보 6개가 나왔으며 서로 독립적인 결함 6개라는 뜻은 아니다. 결과는 Git 제외 `build/review/repeated-java-blocks.json`에 남겼다. 구조가 유사하지만 문자열이 다른 로직은 이 검사에서 탐지하지 못하므로 아래 수동 리뷰와 함께 판단했다.

| 정확히 반복된 코드 | 판단 |
| --- | --- |
| LoginRequest·SignupRequest 이메일 검증 어노테이션 | API 입력 계약을 각각 드러내므로 단순 중복 제거용 DTO 상속은 도입하지 않음 |
| CookieUtil·JwtUtil Access TTL 검증 | 보안 정책 중복 후보. 별도 정책 객체 도입 시 쿠키·JWT 경계 테스트를 함께 유지해야 하므로 현재 범위에서는 보존 |
| ChatCaseEvidence·ChatEvidenceRemoval JPA ID/방 관계 매핑 | 관계·생명주기가 다르므로 공통 엔티티 계층 추가 보류 |
| CaseSearchService·LawParseUtil 예외 생성 | 작은 예외 헬퍼이며 별도 공통 유틸을 도입할 이득이 작음 |

- ChatClient의 clone/defaultSystem/defaultOptions 반복은 역할별 프롬프트·Advisor 차이를 드러낸다. 범용 팩토리로 감추기보다 실제 오류를 만든 schema-only 옵션 생성을 정리한다.
- `LawVectorClient`의 차원·유한 값·norm 검사와 HTTP 경계 콜백은 추출 후보지만 호출자별 실패 정책이 다르다. 오류 의미를 뭉개는 포괄 추상화는 보류한다.
- 로그인은 비관적 사용자 잠금 안에서 BCrypt·Redis 토큰 발급을 수행한다. 연결 점유를 줄일 후보이나 잠금을 제거하면 비밀번호 변경·탈퇴와 경쟁할 수 있다. 자격증명 재확인·발급 취소 계약을 먼저 검증한다.
- 임베딩과 리랭킹은 서버에서 추론 잠금을 공유하지만 Java 입장 제어는 임베딩에만 있다. 연산별 자원 분리·공정성·취소·중첩 대기 관측을 비교할 대상으로 남긴다. 로컬 semaphore는 다중 인스턴스 전역 보호를 대체하지 않는다.
- 방 소유권·근거 무결성 재검사는 서비스 간 신뢰 경계다. 중복 조회를 줄이더라도 독립 호출의 권한 검사를 제거하지 않는다.
- 스트림은 임시 delta와 검증·저장 후 completed를 구분한다. 슬롯 반환·취소 전파가 실제 공급자의 계산·과금 중단을 보장하지 않는 기존 제한을 유지한다.
- 저장 성공 후 SAVED 근거 목록을 재조회하는 단계가 실패하면 저장은 유지되지만 error로 끝날 수 있다. 저장 결과와 근거를 함께 반환하는 방식 또는 커밋 후 오류 계약을 별도 검토한다. 이번 변경에서 저장·완료 계약을 임의로 바꾸지 않는다.

## 검증 상태

2026-10-30에 위 네 항목의 코드 변경을 완료했다. JDK 17.0.20.1+1에서 `.\gradlew.bat test bootJar --console=plain`을 실행해 **555건·실패 0·오류 0·건너뜀 0**, 실행 JAR 생성을 확인했다. 기본 `test`는 유료·외부 저장소 선택 태그를 제외한다.

- `ChatRoomListQueryTest`: H2에서 페이지 크기 1/20/100 모두 SQL 2회, 빈 페이지 1회. 소유권 범위·복호화·빈 방·다음 페이지·동률 ID 정렬·삽입 순서와 다른 timestamp 정렬·입력 오류 경로를 확인했다.
- `StructuredPlannerOptionsTest`: 실제 역할 Bean과 planner의 ChatModel 전달 Prompt에서 모델·effort·완료 한도와 동적 schema를 확인했다. 요청 간 schema가 누적되지 않고 역할 기본 옵션이 변하지 않음을 확인했다.
- `RoleChatClientConfigTest`: 실제 application.yml과 비기본 환경변수로 후속 계획 설정을 확인하고 답변 역할 격리·메모리 미사용을 검증했다.
- 최초 선택 회귀 33건 중 새 테스트의 숫자 판례 ID 계약 위반 fixture가 1건 실패했다. fixture를 유효한 숫자로 고친 뒤 위 전체 회귀를 통과했다. 인용·ID 검증 규칙은 완화하지 않았다.

로컬 기본 JAVA_HOME은 존재하지 않는 경로였고 설치 JDK 21은 Java 17 toolchain을 충족하지 못했다. 공식 Adoptium JDK 17을 Git 제외 `build/toolchains/`에 준비해 실행했으며 저장소의 Java 버전은 바꾸지 않았다. Gradle 캐시는 기존 사용자 캐시를 사용했다.

원본 보고서는 `build/reports/tests/test/index.html`·`build/test-results/test/`, 실행 JAR은 `build/libs/MoleLaw_backend-0.0.1-SNAPSHOT.jar`다. 성능 재측정·실제 유료 모델 호출·실제 MySQL에서 새 배치 쿼리 검증·운영 환경 변경은 포함하지 않는다. 1차의 READ effort=none 기록은 수정하지 않으며, 모킹에서 옵션 보존을 확인한 결과와 이후 실호출을 구분한다. 처리량·p95·비용이 개선됐다는 수치 결론은 아직 없다.
