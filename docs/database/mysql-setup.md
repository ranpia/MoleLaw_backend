# 빈 MySQL DB 초기화와 검증

기본/mysql·local에서 Flyway V1~V7을 순서대로 적용한 뒤 Hibernate가 validate한다. 적용한 버전은 수정하지 않으며 기존 Flyway 관리 DB에는 미적용 migration만 순서대로 추가한다. V4는 구 법령·청크·벡터 테이블을 삭제하므로 이 저장소의 초기 개발 전환 기준으로만 적용한다. 이전 INFO 행·구 암호문은 자동 변환하지 않는다.

2026-10-24 V6은 동일 원문·설정의 동시 색인을 막는 nullable unique `dedup_key`, V7은 [본문 없는 조문 ID/해시 관계](../law/incremental-law-indexing.md)를 추가했다. 기존 원문/벡터는 유지한다. 새 메타데이터는 완료 검증 시 등록하며 기존 데이터의 수동 보강은 `scripts/backfill-law-chunk-index.py`를 사용한다. 별도 개발 DB는 V7과 495개 메타데이터 연결까지 적용했다.

## 실행

1. [Docker 가이드](../../docker/README.md)로 MySQL·Redis·Qdrant를 준비한다. 최초 실행은 빈 DB를 사용하고, 이미 Flyway가 관리하는 DB는 미적용 migration만 추가한다. 이력 없는 기존 DB나 볼륨은 자동 초기화하지 않는다.
2. `.env`의 DB 주소·계정을 맞추고 기존 `JPA_DDL_AUTO` 설정은 `validate`로 맞춘다. 최초 migration 계정에는 테이블/인덱스/FK 생성 권한이 필요하다.
3. OAuth·AI 등 기존 필수 설정과 루트 .env의 CHAT_ENCRYPTION_KEY(무작위 32바이트의 Base64)를 설정하고 실행한다. Spring 설정이 .env를 읽어 암호화 빈에 주입한다. [상담 저장 계약](../chat/chat-storage.md)을 따른다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

루트 `.env`에 `SPRING_PROFILES_ACTIVE=local`이 있으면 인자 없이 `.\gradlew.bat bootRun`으로 실행할 수 있다. YAML이 이 값을 `spring.profiles.active`로 명시적으로 연결하며 CLI 프로필이 우선한다.

Flyway가 `Communications link failure`와 `Connection refused`로 실패하면 SQL 적용 전 DB 연결 실패다. 먼저 MySQL 컨테이너 상태와 `.env`의 호스트·포트를 확인한다. 이 경우 이력을 repair하거나 DB를 초기화하지 않는다. 2026-10-22 로컬 재현에서는 중지된 기존 MySQL·Redis·Qdrant를 시작한 뒤 `.env`만 사용한 실행에서 V1~V4 검증·현재 버전 4·JPA validate·서버 기동을 확인했다. 기존 스키마나 migration 이력은 변경하지 않았다.

`flyway_schema_history`에 버전 1·2·3·4 성공 이력이 남고 서버가 기동되면 초기화 완료다. 재기동 시 적용한 버전을 다시 실행하지 않는다. 테이블은 `user`, `chat_room`, `message`, `legal_source`, `legal_source_index`, `chat_evidence_snapshot`이다. User 숫자 PK는 IDENTITY로 생성한다. V2는 이전 방의 근거를 자동 생성하지 않으며 스냅샷 없는 방의 후속 상담은 실패한다. V3는 사용자 필수값·로컬/OAuth 자격증명 조합·provider별 subject 유일성을 적용하고 비밀번호 컬럼을 `password_hash`로 명시한다. 기존 데이터가 제약을 만족하지 않으면 실패하며 자동 삭제하지 않는다.

이전 `001-legal-source.sql`은 참고용이며 실행하지 않는다. 수집 작업·상담 근거 테이블은 후속 migration으로 추가한다. 로그인 세션·Refresh는 Redis에 구현하며 MySQL 인증 테이블은 추가하지 않는다. V4는 legal_source_index와 상담 제약·LONGTEXT·삭제 cascade·근거 schema_version을 추가하고 law_embedding/law_chunk/law를 제거한다.

## 테스트

```powershell
# Docker·외부 API 키 없이 H2와 모킹으로 회귀 확인
.\gradlew.bat test
# Docker에서 임시 MySQL 8.4.11 컨테이너 생성·검증·정리
.\gradlew.bat mysqlIntegrationTest
```

MySQL 테스트는 실제 DB URL·비밀값 대신 Testcontainers의 임시 포트·테스트 계정을 사용한다. MySQL과 JPA/Flyway만 로드하고 Redis·Qdrant·OAuth·모델·법령 API는 호출하지 않는다. 기본 테스트에서 제외하고 CI는 별도 단계로 실행한다.

검증 범위: 빈 DB migration→모든 엔티티 validate, 원문·색인 상태·큰 암호문 저장/복원, 한글/이모지, DATETIME(6) 정밀도, 이메일/provider·OAuth subject 유일성, 고아 FK 거절, 컨텍스트 재기동 후 데이터 유지·migration 무변경, 이력 없는 비어 있지 않은 DB 거절. 로그인 HTTP 전체 흐름과 Refresh 회전 검증은 포함하지 않는다.

Docker 29 호환을 위해 테스트 태스크의 docker-java API를 1.44로 지정한다. 필요하면 `-PdockerApiVersion=...`로 해당 테스트만 조정한다. Docker 연결 실패는 건너뛰지 않고 테스트 실패로 보고한다.

## 오류와 변경 절차

- **비어 있지 않은 DB·Flyway 이력 없음:** 새 빈 DB를 준비한다. 자동 baseline·clean으로 우회하지 않는다.
- **checksum 불일치:** 적용한 V1을 수정하지 말고 후속 버전 SQL을 추가한다. 이력 삭제/repair로 검증을 우회하지 않는다.
- **JPA validate 실패:** 엔티티와 migration의 타입·컬럼을 비교한다. Hibernate update로 덮어쓰지 않는다.
- **권한 문제:** 필요하면 `SPRING_FLYWAY_URL/USER/PASSWORD` 환경변수로 migration 계정을 앱 계정과 분리한다. 비밀값은 예제·로그에 넣지 않는다.

새 데이터는 백업·복원 대상으로 관리한다. 실제 개발 DB/볼륨 초기화는 별도 대상 확인 작업이며 이 테스트가 수행하지 않는다.

설계와 다음 작업은 [MySQL 계획](../plans/mysql-schema.md), 실제 SQL은 [V1](../../src/main/resources/db/migration/V1__initial_schema.sql)을 따른다.

검증 이력 (2026-10-13): `test mysqlIntegrationTest bootJar` 통과. 일반 테스트 95개, 실제 MySQL 테스트 2개, 실행 JAR 패키징을 확인했다. 테스트용 컨테이너만 사용했으며 기존 개발 DB·볼륨은 변경하지 않았다.

## 로컬 개발 DB 재생성 기록 (2026-10-21)

기존 데이터를 보존하지 않는다는 결정에 따라 `molelaw-local-mysql-1`의 `127.0.0.1:3307/molelawdb`를 재생성했다. 기존 DB는 Flyway 이력이 없고 `user_seq`가 남은 구 스키마였다. 사용자뿐 아니라 같은 DB의 상담·법령 테이블 데이터도 초기화했으며 V1·V2·V3를 Flyway로 새로 적용했다. 다른 DB·Docker 볼륨·Qdrant 컬렉션·임베딩 모델 캐시는 변경하지 않았다.

실행 시 다른 DB 연결이 없는지 확인했다. 사용자 0건, V3 적용, 구 `user_seq` 제거를 확인했다. 기존 사용자 ID·시퀀스 범위를 고려해 새 사용자 AUTO_INCREMENT를 152로 설정하여 기존 Access/Refresh의 사용자 ID가 새 계정에 재사용되지 않도록 했다. Redis는 초기화하지 않았으며 기존 세션은 만료된다. 이전 로그인은 사용할 수 없으므로 새로 가입·OAuth 인증해야 한다.

검증: 일반 테스트 256개(실패 0, 건너뜀 1), 격리 MySQL 통합 테스트 2개 통과, `bootJar` 생성 완료. 잘못된 로컬/OAuth 자격증명 조합, 필수값, 이메일 중복, subject 대소문자 구분·제공자별 유일성, 재기동 시 migration 무변경을 검증했다. 실제 Google/Kakao 로그인은 호출하지 않았다. 일반 테스트·서버 기동·migration에는 자동 DB 삭제 기능을 추가하지 않았다.
## V4 적용·검증 완료 (2026-10-22)

로컬 `molelaw-local-mysql-1`의 `127.0.0.1:3307/molelawdb`를 확인하고 V3→V4를 적용했다. 이번에는 DB를 다시 삭제하지 않았다. Flyway 4개 checksum 검증과 재실행 migration 0건을 확인했다. 업무 테이블은 user/chat_room/message/chat_evidence_snapshot/legal_source/legal_source_index이며 구 law/law_chunk/law_embedding은 제거됐다. Qdrant 실험 컬렉션과 모델 캐시는 변경하지 않았다.

검증 결과: 일반 테스트 282개(실패 0, 환경변수로 선택 실행하는 Postman 테스트 1개 건너뜀), MySQL 통합 2개·Qdrant 통합 1개·실제 KURE 임베딩 1개 통과, 실행 JAR 빌드 완료. 실제 MySQL에서 64KiB를 넘는 한글 암호문의 LONGTEXT 복원·사용자 삭제 cascade·V1~V4 신규 적용·재기동을 확인했다. KURE 검증은 통제된 20개 조문→40개 청크→1024차원→서로 다른 상위 15개 조문이며 검색 정확도 평가는 아니다.

추가로 기존 로컬 Postman simulation에서 실제 클라이언트의 /api/tags·/api/embed 두 요청과 HTTP 200, 선택 테스트 1개 통과를 확인했다. 실제 OpenLaw·OAuth·유료 LLM을 연결한 전체 상담 실호출은 이번 검증에 포함하지 않았다.

V5는 기존 법령 원문·최초 상담 스냅샷을 변경하지 않고 `chat_case_evidence`와 메시지의 `case_search_requested`·`case_search_consent`를 추가한다. 새 판례 근거는 방 삭제 시 cascade 삭제되며 원문은 보존한다. `mysqlIntegrationTest`에서 빈 MySQL의 V1~V5 적용·JPA validate·재시작 후 데이터 유지·cascade를 확인했다. 사용자 실행 DB는 이 검증의 대상이 아니며 재초기화하지 않았다.
