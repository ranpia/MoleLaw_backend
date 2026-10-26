# MySQL 후속 계획

연결 작업: [P05·P08](README.md). 현재 테이블·제약은 [스키마 현황](../database/schema-rebuild-status.md), 실제 적용 절차는 [MySQL 실행](../database/mysql-setup.md)을 따른다.

## 완료된 기반

기존 데이터 이관 없이 시작하는 정책과 Flyway V1~V5, 기본/local JPA validate를 적용했다. 현재 테이블은 user·chat_room·message·legal_source·legal_source_index·chat_evidence_snapshot·chat_case_evidence이다. 사용자 검증·암호화 LONGTEXT·삭제 cascade·불변 원문·세대 상태·최초 근거 스냅샷을 구현했다.

인증 세션·Refresh MySQL 테이블은 추가하지 않는다. Access는 stateless, Refresh는 Redis가 담당한다. 구 law/law_chunk/law_embedding과 MySQL 벡터 검색 경로는 제거했다.

## P05 — 작업·턴 상태 설계

- 요청 식별자와 중복 제출·같은 방 동시 턴의 처리 정책을 정의한다.
- 영속 수집 작업·페이지 커서·배치 완료·취소/실패·lease 또는 소유자를 설계한다. 테이블명·상태 enum은 아직 확정하지 않는다.
- 서버 재시작 시 오래된 WRITING을 회수하고 이전 COMPLETE의 가용성을 보존한다.
- 삭제된 방·계정의 늦은 결과가 성공 턴을 재생성하지 못하도록 저장 시점에 검사한다.

비동기 API는 D04, 원문·작업·구세대 보존은 D09와 함께 결정한다. Qdrant/MySQL/Redis의 단일 트랜잭션을 가정하지 않는다.

## P08 — 운영 스키마·복구

DDL 계정과 앱 계정을 분리하고 백업→복원→Qdrant 재색인·활성 세대 확인을 검증한다. 암호화 키 버전·복원 시 키 가용성·계정 삭제 cascade를 점검한다. Redis 유실을 MySQL에서 자동 복원하지 않는다.

적용된 migration은 수정하지 않고 새 버전을 추가한다. 실제 DB·볼륨 초기화와 대량 삭제는 명시된 대상에 대한 별도 실행 작업이다. 기존 빈 DB 시작 결정만으로 현재 개발 데이터를 다시 지우지 않는다.

## 완료 조건

H2 회귀와 독립 MySQL 신규 적용·재기동·이전 버전 전환을 분리 검증한다. 부분 실패·중복·동시 실행·삭제 경쟁·복원 시 미공개 세대 노출이 없어야 한다. 구현 PR에 새 migration·역전 불가 변경·복구 절차를 함께 적는다.
