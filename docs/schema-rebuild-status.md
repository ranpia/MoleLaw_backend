# 스키마 개편 적용 현황

기준: 2026-10-23, `dev` 작업 내용. [이전 속성 검토](schema-attribute-review.md)의 사용자·상담·법령 저장 모델 변경을 적용했다. 검증 결과와 실제 DB 적용 여부는 [MySQL 실행 가이드](mysql-setup.md)에 별도로 기록한다.

## 적용한 변경

| 영역 | 현재 계약 |
| --- | --- |
| 사용자 | HTTP `@Valid`와 서비스 `@Validated`, BCrypt 72바이트 제한, 정규화 이메일, OAuth subject 식별, V3 자격증명 제약 |
| 상담방 | 사용자·제목·UTC 생성 시각 필수, 제목 255 코드포인트, 최신 생성 시각·ID 내림차순 페이지 조회 |
| 메시지 | USER/BOT·요청별 판례 검색 동의/요청 표시 저장, LONGTEXT, 질문 2,000 코드포인트·32KiB, 답변 1MiB, 시각·ID 오름차순 |
| 암호화 | Spring이 .env/환경변수에서 주입하는 32바이트 키, AES-256-GCM, 메시지별 nonce, 인증 태그와 키 버전 envelope |
| 근거 | schema_version=2, 원문 UUID·MST·LID·시행일·조문 경로·모델 revision·설정 fingerprint·generation 고정 |
| 후속 판례 | V5 chat_case_evidence에 원문·근거 UUID 불변 참조, 방·판례 유일성, 방 삭제 cascade |
| INFO | DB 중복 저장 제거, 최초 BOT 다음에 고정 스냅샷에서 재구성하여 기존 응답 유지 |
| 삭제 | 방의 역방향 큰 객체 관계 제거, 사용자→방→메시지·스냅샷 FK cascade |
| 법령 원문 | 불변 legal_source 유지, 숫자 식별자·실제 날짜·공식 URL 검증, 조회 인덱스 |
| 색인 | legal_source_index의 WRITING/COMPLETE/FAILED, 원문 해시·모델·설정·세대별 기록 |
| 검색 | COMPLETE 세대·일치하는 설정만 Qdrant 검색, 반환 출처를 MySQL과 재검증 |
| 구 저장 경로 | law/law_chunk/law_embedding 및 MySQL 벡터 검색 코드, /api/test/import 제거 |

## 최초·후속 상담

최초 질문은 Qdrant 상위 15개 조문을 조회하고 전용 근거 판단 Client를 호출한다. 서버는 ANSWER_READY에서 선택된 근거로 답변하고, SEARCH_AGAIN에서 다른 질의를 검색하거나 COLLECT_LAW에서 목록·원문 수집→벡터화→재검색한다. 일반 검색 조건의 전체 페이지를 평가한 뒤 70점 이상 상위 2개를 선별한다. 전체 검토 전 예산이 끝나면 해당 조건의 부분 후보를 색인하지 않는다. 선택 법령 한 건의 색인·검증 후 재검색한다.

현재 서버 상한은 실행 작업 4회와 최종 판단 1회, 검색·수집 HTTP 128회, 일반 처리 3분, 조건별 전체 페이지(예산 내), 상세 법령 6건이다. 질문·검색어·원문 ID·예산은 모델이 직접 권한으로 사용할 수 없다. 근거가 늘지 않거나 소진되면 422 EVIDENCE_INSUFFICIENT, 서비스 비활성·실패 또는 임베딩 과부하는 503이다. 시간은 호출 경계에서 검사하며 실행 중 HTTP를 즉시 취소하는 기능은 아니다.

후속 상담은 Advisor의 방 기록과 최초 법령 근거를 사용한다. 사용자 동의 후 읽고 인용한 판례는 V5의 chat_case_evidence에 별도 누적한다. 새로운 법령 수집·색인은 하지 않는다. 서로 다른 MST는 과거 법령 질의에 필요할 수 있어 일괄 최신 시행일로 덮어쓰지 않으며, 확보한 시행일을 답변 입력에 제공한다.

## 로컬 부하와 남은 운영 작업

임베딩은 애플리케이션 프로세스당 동시 배치 1개·대기 4개·대기 5초를 기본으로 제한한다. 실패·중단 세대의 Qdrant 점은 검색되지 않지만 물리 정리는 별도 운영 작업이다. 동일 본문·설정의 완료 색인은 실제 저장 포인트 검증을 통과한 경우에만 재사용한다. 임베딩 시간은 일반 타이머와 분리해 누적 900초를 적용한다.

남은 작업과 정책 선택은 [작업 계획](plans/README.md)·[사용자 판단](decisions/README.md)으로 분리한다. SSE는 후순위다. Reader/Transformer/Writer 연결은 완료했다. 전체 영속 작업 큐·중단 복구는 완료하지 않았다.

실행 계약: [상담 저장](chat-storage.md), [법령 벡터 도구](law-vector-tools.md), [판단 계약](evidence-decision.md), [사용자 검증](user-account-validation.md).
## 검증 완료 (2026-10-22)

일반 테스트 282개(실패 0·선택 실행 1개 건너뜀), MySQL 2개·Qdrant 1개·실제 KURE 1개와 JAR 빌드를 통과했다. 선택 Postman HTTP 테스트는 별도로 1개 통과했다. 로컬 molelawdb에 V4를 적용했고 checksum·재실행 무변경을 확인했다. 실제 OpenLaw/OAuth/유료 LLM 전체 상담 평가는 포함하지 않았다.
