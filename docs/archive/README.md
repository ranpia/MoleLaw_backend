# 과거 참고 자료

이 폴더는 현재 실행 경로·정책·진행 상태와 분리한 기록이다. 이전 API·인증·스키마 검토는 당시 판단을 보존하며 현재 취약점이나 할 일 목록을 의미하지 않는다.

- [과거 API 기준선](backend-baseline.md)
- [과거 JWT 보안 검토](jwt-security-review.md)
- [과거 스키마 속성 검토](schema-attribute-review.md)
- sql/001-legal-source.sql: Flyway 이전 수동 SQL. 실행하지 않는다.

현재 설명은 [문서 안내](../README.md), 정책 선택은 [사용자 판단](../decisions/README.md), 개발 순서는 [작업 계획](../plans/README.md)을 따른다. 실행 SQL은 src/main/resources/db/migration의 Flyway V1~V4이며 적용된 migration을 archive로 옮기거나 수정하지 않는다.
