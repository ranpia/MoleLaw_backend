# MySQL 스키마·마이그레이션 계획

기준: `dev`, `ec9d87b` (2026-10-13). 코드 검토에 기반한 계획이며 실제 DB 스키마·데이터는 이번에 조회하거나 변경하지 않았다. 다음 구현도 `dev`에서 진행한다.

구현 갱신: 같은 `dev` 작업에서 Flyway core/MySQL 모듈·V1 초기 스키마·local validate·User IDENTITY와 독립 MySQL 통합 테스트를 추가했다. 실제 개발 DB·볼륨은 변경하지 않았으며 아래 미구현 테이블은 계획이다.

**확정: 기존 데이터 이관 없이 빈 MySQL DB에서 시작한다.** 기존 DB baseline 등록·데이터 정제·ID 이관은 이번 범위에서 제외한다. 실제 DB/볼륨 초기화는 대상과 실행 방법을 특정하는 별도 작업이며 이 문서 수정에서는 수행하지 않는다.

## 현재 상태

| 영역 | 구현 | 남은 점 |
| --- | --- | --- |
| 실행 | MySQL 8.4 Compose, Flyway V1, 기본/local `validate` | 후속 기능별 migration 추가 |
| 사용자·채팅 | `user`, `chat_room`, `message` JPA | 실제 제약·ID 생성·정렬·조회 인덱스 확인 |
| 법령 | `law`, `law_chunk`, `law_embedding` | MST별 버전·해시·ETL 상태 미관리, 기존 벡터 경로 유지 중 |
| 원문 | `legal_source` 불변 JSON·해시·UUID, V1 생성·실제 MySQL 저장 검증 | 법령 ETL·상담방 근거 연결 |
| 인증 | Redis Access 등록·TTL·검증과 Refresh 해시·원자 회전·family/전체 폐기 | Access stateless·issuer/audience 및 운영 Redis 복구 |

도메인 패키지 이동은 테이블 재생성 사유가 아니다. 목표에서 MySQL은 원본·업무·작업 상태, Qdrant는 재생성 가능한 검색 벡터, Redis는 Refresh 해시·family·회전·폐기 상태를 관리한다. Access는 5분 stateless JWT다. 현재 Redis Access 등록 구현은 전환 대상이다.

## 1. 빈 DB용 초기 스키마 확정

- [ ] 현재 엔티티와 쿼리에서 초기 테이블/컬럼/PK/FK/인덱스를 설계하고 MySQL 8.4에서 검증한다. Hibernate 추정 DDL만으로 완료하지 않는다.
- [ ] 문자셋·콜레이션·시간대를 명시하고 이메일/법령 식별자의 비교·정규화 정책을 정한다.
- [x] `User.id`를 다른 숫자 PK와 같은 IDENTITY로 변경했다. 초기 SQL의 AUTO_INCREMENT와 JPA 매핑을 맞추고 생성기 보조 테이블을 사용하지 않는다.
- [ ] `(email, provider)` 유일성·필수값, OAuth 계정의 nullable password, FK 삭제 정책, 긴 암호화 메시지·원문 크기를 초기 스키마에서 검증한다.

완료 조건: 빈 DB용 초기 DDL·JPA 매핑·제약/인덱스 명세가 일치하고 가입·채팅·법령 원문 저장이 동작한다. 기존 데이터 변환 스크립트는 만들지 않는다.

## 2. Flyway 도입

Spring Boot BOM과 호환되는 Flyway core·MySQL 지원 모듈을 확인하고 `src/main/resources/db/migration/`에 순차 SQL을 둔다. 클래스는 `common.config`, 업무 엔티티는 소유 도메인에 유지한다.

| 대상 | 전환 방식 |
| --- | --- |
| 빈 DB | 검증한 초기 스키마부터 전체 마이그레이션 실행 → JPA validate |
| 기존 DB | 이관·baseline 등록 대상에서 제외. 대상이 비어 있지 않으면 자동 우회하지 않고 중단 |
| `001-legal-source.sql` | 구조를 초기 migration에 포함하고 수동 적용 경로를 폐기 문서로 명시. 새 DB에서 둘 다 실행하지 않음 |
| H2 test | Docker 없는 기존 회귀 테스트 유지. MySQL SQL을 H2에서 통과시키는 것으로 통합 검증을 대체하지 않음 |

초기 파일은 `V1__initial_schema.sql`로 시작하며 현재 실행 경로가 사용하는 테이블을 포함한다. 미구현 ETL·상담 근거 테이블은 해당 기능의 후속 migration으로 추가한다. 인증 세션/Refresh는 Redis에 두므로 MySQL 테이블을 추가하지 않는다. `baseline-on-migrate`는 사용하지 않는다. 적용된 버전 SQL은 수정하지 않고 새 버전을 추가한다. local도 `validate`로 바꾸고 자동 `update` 의존을 제거한다. 애플리케이션 실행 계정과 마이그레이션 DDL 계정의 권한을 분리한다.

MySQL DDL은 암묵적 commit이 발생할 수 있으므로 여러 SQL을 하나의 rollback으로 되돌릴 수 있다고 가정하지 않는다. 부분 적용 확인·보정 migration·백업 복원 절차를 준비한다. [MySQL DDL](https://dev.mysql.com/doc/refman/8.4/en/implicit-commit.html), [Flyway 기준선](https://documentation.red-gate.com/fd/baselines-273973441.html)

## 3. 인증 저장소 경계 — Redis Refresh로 변경

2026-10-13 결정으로 기존 `auth_session`·`refresh_token` MySQL 테이블 제안은 철회한다. Access는 5분 stateless JWT, Refresh 해시·session/family·회전·폐기·사용자별 인덱스는 Redis가 소유한다. 인증 구현은 `auth` 도메인에 둔다. 기존 V1과 업무 테이블은 이 결정만으로 변경하지 않는다.

- 사용자 계정과 비밀번호는 MySQL에 유지한다. 가입·비밀번호 변경·탈퇴의 MySQL 작업과 Redis 상태 변경은 단일 트랜잭션이 아니므로 부분 실패·재시도 정책을 검증한다.
- MySQL 복원으로 Redis Refresh를 복원하지 않는다. 사용자 조회·권한·상담방 소유권 검사는 stateless 토큰 검증과 별개다.
- 지속 보관할 보안 감사 이벤트는 원문 토큰 없이 별도 설계한다. 감사 기록을 Refresh 활성 상태의 대체 저장소로 사용하지 않는다.
- 로그아웃·전체 폐기는 Refresh 추가 발급을 차단한다. 기존 Access는 최대 5분 남을 수 있으며 즉시 차단을 보장하지 않는다.

완료 조건: 실제 Redis에서 한 Refresh의 동시 소비 1회·재사용 탐지·폐기·TTL·복구를 검증하고, 실제 MySQL에서는 계정 변경의 부분 실패를 검증한다. [인증 상세 계획](auth-security.md)을 따른다.

## 4. 법령 버전·수집·ETL 스키마

업무 소유권은 `law`다. 기존 `legal_source`의 수집별 스냅샷은 유지하고 원문 중복 제거와 법령 버전의 식별을 별도로 설계한다.

| 테이블 제안 | 책임 |
| --- | --- |
| `law_document_version` | 법령ID·MST·시행일·원문 참조·내용 해시. 같은 법령의 개정본과 동일 MST의 내용 변화를 구분 |
| `law_collection_job` / `law_collection_page` | 탐색 조건·페이지·처리 후보·시도·마지막 성공 위치·종료 사유. 정상 소진과 예산 종료 구분 |
| `law_index_job` | 원문 버전·모델/digest·차원·청킹/색인 버전·배치·성공/실패·재시도 |
| `law_index_chunk` | 결정적 Qdrant UUID·원문/조문 경로·청크 본문/해시·색인 상태. 기존 `law_chunk` 확장과 새 테이블 중 선택 |

실행에는 임대 만료/낙관적 버전 등 소유권을 두어 서버 중단 후 재개와 늦은 결과 저장을 제어한다. 조건·페이지/청크의 업무 유일성을 DB 제약으로 보장한다. MySQL commit 후 Qdrant 작업 실패는 상태 기록·멱등 재시도로 복구하고 분산 단일 트랜잭션을 가정하지 않는다.

완료 조건: 같은 입력 재실행·변경·삭제·중간 실패·재시작 재개, 버전 혼합 방지, 활성 색인 전환/복구. 기존 데이터는 이관하지 않지만 현재 코드가 사용하는 `law_embedding` 테이블은 초기 스키마에 둔다. Qdrant 전환 후 호출 경로와 함께 제거한다. 새 MySQL 데이터와 맞지 않는 기존 Qdrant 컬렉션은 재사용하지 않고 별도 컬렉션에 재색인한다.

## 5. 상담방 근거·턴 저장

`chat` 도메인에 최초 처리 상태/요청 식별자와 `chat_evidence_snapshot`·`chat_turn`을 제안한다. 근거는 원문 참조·필요 본문·출처·조회/시행일·부족 사유를 보존하고 후속 질문에서 교체하지 않는다.

`(room_id, request_id)` 중복 방지와 턴 순서·성공/실패 상태를 정의한다. USER/BOT 원본과 Advisor 입력 윈도우를 구분하고 검증 성공한 턴만 확정한다. 외부 조회·모델 호출은 트랜잭션 밖에서 실행한다. 방 삭제와 진행 작업 완료가 경합해도 데이터가 재생성되지 않아야 한다.

인덱스 후보는 `chat_room(user_id, created_at, id)`, `message(chat_room_id, timestamp, id)`다. 실제 조회를 같은 순서로 정렬·페이지 처리하도록 고친 뒤 EXPLAIN으로 검증한다. 기존 FK 인덱스와 중복 생성하지 않는다. 암호화 본문을 검색 인덱스로 사용하지 않는다.

## 실행 단위와 합격 기준

1. **초기 스키마·마이그레이션 PR:** ID/제약 설계·Flyway·V1 SQL·local validate·독립 MySQL 테스트 DB. H2 테스트 유지.
2. **빈 DB 통합 검증:** 가입→로그인→채팅 저장→법령 원문 저장, 재기동 후 데이터 보존·migration 재실행 무변경 확인.
3. **인증 저장소 PR:** Redis Refresh 발급/회전/폐기와 Access 5분 stateless 전환, API·Swagger·프론트 계약 갱신. MySQL session/Refresh migration은 만들지 않는다.
4. **법령 ETL PR:** 버전/수집/청크/색인 상태와 Reader→writer 연결.
5. **상담 저장 PR:** 최초 상태·근거·성공 턴·Advisor·삭제/동시성 검증.

실제 MySQL 8.4 통합 테스트는 전용 DB에서 빈 DB migrate→validate, 재실행 무변경, FK/unique·시간대·긴 원문/암호문을 검증한다. Refresh 동시 회전은 Redis 통합 테스트에서 검증한다. 테스트는 임시 전용 DB/볼륨을 사용하고 실제 개발 DB 초기화와 분리한다. 전환 후 생기는 새 데이터는 백업·복원 대상으로 관리한다.

빈 DB 시작과 기존 데이터 이관 제외, User IDENTITY, Access 5분 stateless + Redis Refresh는 확정 사항이다. 추가로 결정할 것은 시간대·정규화 정책, Refresh 세부 정책, 버전/청크 키다. 테이블을 한 번에 모두 만들지 않고 각 기능의 계약·완료 조건이 정해진 단계에서 추가한다.
