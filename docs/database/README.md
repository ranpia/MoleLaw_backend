# 데이터베이스

사용자·상담·법령 원문과 색인 메타데이터를 저장하는 MySQL 스키마와 실행 설정을 설명한다. Flyway V1~V8로 스키마를 적용하고 Hibernate validate로 엔티티와의 일치를 확인한다. 상담 메시지는 암호화하며 방별 근거 스냅샷·추가 판례·근거 제외 기록을 보존한다.

Refresh 상태는 Redis, 검색 벡터는 Qdrant에 저장하며 MySQL과 하나의 트랜잭션으로 묶이지 않는다. 기존 DB를 자동 초기화하거나 구 스키마 데이터를 자동 변환하지 않는다. H2 테스트만으로 실제 MySQL 동작이나 Qdrant 연계를 보장하지 않는다.

- [빈 MySQL DB 초기화와 검증](mysql-setup.md)
- [스키마 개편 적용 현황](schema-rebuild-status.md)

[전체 문서](../README.md) · [프로젝트 README](../../README.md)
