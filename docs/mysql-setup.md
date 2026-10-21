# 빈 MySQL DB 초기화와 검증

기존 데이터 이관 없이 시작한다. 기본/mysql·local에서 Flyway가 V1 기본 스키마와 V2 상담방 근거 스냅샷을 순서대로 적용한 뒤 Hibernate가 `validate`한다. V1 적용 DB에는 V2만 추가한다. 수동 SQL이나 `ddl-auto=update/create`는 사용하지 않는다.

## 실행

1. [Docker 가이드](../docker/README.md)로 MySQL·Redis·Qdrant를 준비한다. `MYSQL_DATABASE`가 가리키는 DB는 비어 있어야 한다. 기존 볼륨은 자동 삭제하지 않는다.
2. `.env`의 DB 주소·계정을 맞추고 기존 `JPA_DDL_AUTO` 설정은 `validate`로 맞춘다. 최초 migration 계정에는 테이블/인덱스/FK 생성 권한이 필요하다.
3. OAuth·AI 등 기존 필수 설정을 제공하고 실행한다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

`flyway_schema_history`에 버전 1·2 성공 이력이 남고 서버가 기동되면 초기화 완료다. 재기동 시 적용한 버전을 다시 실행하지 않는다. 테이블은 `user`, `chat_room`, `message`, `law`, `law_chunk`, `law_embedding`, `legal_source`, `chat_evidence_snapshot`이다. User 숫자 PK는 IDENTITY로 생성한다. V2는 이전 방의 근거를 자동 생성하지 않으며 스냅샷 없는 방의 후속 상담은 실패한다.

이전 `001-legal-source.sql`은 참고용이며 실행하지 않는다. 수집 작업·상담 근거 테이블은 후속 migration으로 추가한다. 로그인 세션·Refresh는 Redis에 구현하며 MySQL 인증 테이블은 추가하지 않는다. 현재 벡터 서비스가 사용하는 `law_embedding`은 Qdrant 전환까지 유지한다.

## 테스트

```powershell
# Docker·외부 API 키 없이 H2와 모킹으로 회귀 확인
.\gradlew.bat test
# Docker에서 임시 MySQL 8.4.11 컨테이너 생성·검증·정리
.\gradlew.bat mysqlIntegrationTest
```

MySQL 테스트는 실제 DB URL·비밀값 대신 Testcontainers의 임시 포트·테스트 계정을 사용한다. MySQL과 JPA/Flyway만 로드하고 Redis·Qdrant·OAuth·모델·법령 API는 호출하지 않는다. 기본 테스트에서 제외하고 CI는 별도 단계로 실행한다.

검증 범위: 빈 DB migration→모든 엔티티 validate, 원문·벡터·암호문 저장/복원, 한글/이모지, DATETIME(6) 정밀도, 이메일/provider·청크 임베딩 유일성, 고아 FK 거절, 컨텍스트 재기동 후 데이터 유지·migration 무변경, 이력 없는 비어 있지 않은 DB 거절. 로그인 HTTP 전체 흐름과 Refresh 회전 검증은 포함하지 않는다.

Docker 29 호환을 위해 테스트 태스크의 docker-java API를 1.44로 지정한다. 필요하면 `-PdockerApiVersion=...`로 해당 테스트만 조정한다. Docker 연결 실패는 건너뛰지 않고 테스트 실패로 보고한다.

## 오류와 변경 절차

- **비어 있지 않은 DB·Flyway 이력 없음:** 새 빈 DB를 준비한다. 자동 baseline·clean으로 우회하지 않는다.
- **checksum 불일치:** 적용한 V1을 수정하지 말고 후속 버전 SQL을 추가한다. 이력 삭제/repair로 검증을 우회하지 않는다.
- **JPA validate 실패:** 엔티티와 migration의 타입·컬럼을 비교한다. Hibernate update로 덮어쓰지 않는다.
- **권한 문제:** 필요하면 `SPRING_FLYWAY_URL/USER/PASSWORD` 환경변수로 migration 계정을 앱 계정과 분리한다. 비밀값은 예제·로그에 넣지 않는다.

새 데이터는 백업·복원 대상으로 관리한다. 실제 개발 DB/볼륨 초기화는 별도 대상 확인 작업이며 이 테스트가 수행하지 않는다.

설계와 다음 작업은 [MySQL 계획](plans/mysql-schema.md), 실제 SQL은 [V1](../src/main/resources/db/migration/V1__initial_schema.sql)을 따른다.

검증 이력 (2026-10-13): `test mysqlIntegrationTest bootJar` 통과. 일반 테스트 95개, 실제 MySQL 테스트 2개, 실행 JAR 패키징을 확인했다. 테스트용 컨테이너만 사용했으며 기존 개발 DB·볼륨은 변경하지 않았다.
