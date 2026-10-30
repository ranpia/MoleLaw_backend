> 과거 기록입니다. 현재 계약·할 일로 사용하지 마세요. [현재 문서 안내](../README.md)를 우선합니다.

# 사용자·상담·법령 속성 재검토

> 2026-10-22 갱신: 사용자·상담·법령 개편을 구현했다. 아래 기존 검토·계획의 미완료 표기보다 [현재 적용 현황](../database/schema-rebuild-status.md)을 우선한다. 실제 DB 적용·검증 결과는 MySQL 실행 가이드에 별도로 기록한다.

기준: 2026-10-21, `dev`. 엔티티·Flyway V1/V2·현재 운영 호출 코드·테스트를 대조한 정적 검토다. 실제 DB 스키마/데이터를 조회하거나 변경하지 않았으며, 실행하지 않은 DB 검증을 완료로 표시하지 않는다. 기존 판단 계약·실호출 테스트 작업은 그대로 보존한다.

## 결론과 우선순위

즉시 삭제할 사용자 DB 컬럼은 없다. 법령의 구 테이블은 아직 최초 상담에 사용되므로 먼저 새 검색 경로를 연결해야 한다. 우선 수정할 부분은 다음과 같다.

1. **메시지 용량:** 암호문을 `TEXT`에 저장하면서 근거 JSON은 2MiB까지 허용한다. 유효한 큰 근거로 모델을 호출한 뒤 INFO 저장이 실패할 수 있다. 본문 타입·암호화 오버헤드·요청/답변/근거 한도를 함께 맞춘다.
2. **현재 법령 경로의 빈 DB/벡터 정합성:** 빈 결과 수집 후 재조회 없이 `chunks.get(0)`에 접근한다. 본문 변경 뒤 구 임베딩이 유지되고 모델 변경도 기존 행 존재 검사로 건너뛴다. 신구 검색 경로를 연결하기 전 회귀 기준으로 고정해야 한다.
3. **계정 식별:** OAuth를 이메일로 찾고 공급자 고유 subject를 저장하지 않는다. 인증 식별자와 연락처를 분리한다.
4. **필수 제약:** 사용자·방·메시지의 실제 필수 속성 대부분이 nullable이다. 입력 검증과 DB 제약을 함께 적용한다.
5. **과거 구조 정리:** INFO 중복 저장, 잘못된 법령 경로 컬럼, 큰 스냅샷의 역방향 eager 관계를 단계적으로 정리한다.

## 1. user

| 속성 | 현재 실제 사용 | 판정·권장안 |
| --- | --- | --- |
| `id` | JWT subject, Refresh 사용자 키, 방 소유권 | 유지. 이메일과 분리된 내부 PK 사용 |
| `email` | 로컬 로그인, 소셜 조회, 프로필 | 유지·보완. 형식/길이/공백·대소문자 정규화 정책을 일치시킨다. 현행 이메일 필수 계약에서는 NOT NULL 적용 |
| `password` | 로컬 가입·로그인·변경의 해시 | 유지. `password_hash`로 의미 명확화 권장. 로컬 필수·소셜 NULL로 통일하고 현재 소셜의 빈 문자열 관행 제거 |
| `nickname` | 방과 독립된 프로필 표시명·변경 | 유지. unique 불필요. 표시명 길이·빈 값·NULL 정책을 요청과 DB에서 일치시킨다 |
| `provider` | local/google/kakao 분기·비밀번호 변경 제한 | 유지·보완. 자유 문자열/nullable/255자 대신 지원 값과 길이를 제한 |
| `chatRooms` | DB 컬럼 아님. 탈퇴 시 JPA cascade | 즉시 삭제 금지. 직접 getter가 없어도 방·메시지·근거 삭제 동작을 담당 |

**추가할 식별자:** 현재 단일 공급자 계정 구조에서는 `provider_subject`와 `(provider, provider_subject)` 유일성을 추가한다. OAuth 조회·가입을 이 키로 전환하고 이메일만 같다는 이유로 계정을 병합하지 않는다. subject는 대소문자를 보존하고 case-sensitive 비교를 사용한다. 현재 DB 기본 collation은 `utf8mb4_0900_ai_ci`이므로 기본 비교를 그대로 쓰지 않는다.

현재 `(email, provider)` unique는 같은 이메일의 로컬/Google/Kakao 별도 계정을 허용한다. `CustomOAuth2UserService`와 `OAuth2SuccessHandler`가 각각 생성 경로를 갖고 있어 사용자 생성은 한 곳으로 모으고 성공 핸들러는 확인된 내부 ID로 토큰만 발급하도록 정리한다. 공급자 subject를 과거 이메일로 계산해 자동 backfill할 수는 없다. 이메일 없는 공급자를 지원한다면 그때 로컬 이메일 필수 조건과 소셜 연락처 NULL 정책을 분리한다.

가입 사전 중복 조회만으로 동시 가입을 막을 수 없다. DB unique 위반도 도메인 중복 오류로 변환한다. 새 비밀번호·프로필 요청의 검증, 비밀번호 변경 시 현재 비밀번호/최근 재인증은 별도 API 동작 개선 대상이다. 생성/변경 시각은 감사 목적이 확정되면 추가하며, 근거 없이 역할·상태 테이블을 먼저 만들지 않는다.

근거: [User](../../src/main/java/com/MoleLaw_backend/user/entity/User.java), [UserService](../../src/main/java/com/MoleLaw_backend/user/service/UserService.java), [OAuth 사용자 처리](../../src/main/java/com/MoleLaw_backend/auth/oauth/CustomOAuth2UserService.java), [성공 핸들러](../../src/main/java/com/MoleLaw_backend/auth/oauth/OAuth2SuccessHandler.java).

## 2. chat_room

| 속성 | 현재 실제 사용 | 판정·권장안 |
| --- | --- | --- |
| `id` | API 방 ID·메모리 키·근거/메시지 FK | 유지 |
| `user_id` | 소유권 검사·사용자 방 목록 | 유지, NOT NULL 필요 |
| `title` | 최초 질문 키워드 추출의 summary·목록 표시 | 유지. 255자 DB 한도에 맞는 요청/모델 결과 검증과 NOT NULL 필요 |
| `created_at` | 방 목록 응답·목록 정렬 기준 | 유지. NOT NULL과 UTC 저장/표시 정책 명확화 |
| `messages` | DB 컬럼 아님. JPA 삭제 cascade | 조회용 getter는 미사용이나 삭제에 필요. 명시적 삭제나 DB cascade로 대체하기 전 제거 금지 |
| `evidenceSnapshot` | DB 컬럼 아님. 역방향 1:1과 삭제 cascade | 기본 EAGER라 방 조회 시 큰 JSON을 함께 읽을 수 있음. 직접 조회는 별도 Repository로 하므로 역방향 관계 제거 또는 명시적 로딩/삭제 설계 권장 |

`message` FK에는 DB 삭제 cascade가 없고 스냅샷 FK에는 있다. 관계 하나를 지우기 전에 회원 탈퇴→방→메시지→스냅샷 삭제를 통합 검증해야 한다. 컬렉션을 유지한다면 Lombok Builder의 초기값 누락도 `@Builder.Default` 또는 생성 팩터리로 정리한다.

방 목록은 현재 정렬 없는 `findByUser`다. `(created_at, id)` 정렬·페이지 조회로 기존 인덱스와 맞춘다. 호출되지 않는 `ChatService.createChatRoom`은 스냅샷 없는 방을 만들 수 있는 과거 공개 메서드이므로 제거 후보이며, 현재 최초 상담은 `ChatTurnService.saveInitialTurn`으로 생성한다.

토큰 사용량·수집/임베딩 진행 상태를 방 컬럼에 누적해서 붙이지 않는다. 여러 턴·실행·모델별 정산과 재시도를 표현할 별도 실행/사용량 기록이 필요하다. SSE는 그 상태를 전달하는 후속 통신 기능이며 현재 스키마에 즉시 추가할 필드는 아니다.

## 3. message

| 속성 | 현재 실제 사용 | 판정·권장안 |
| --- | --- | --- |
| `id` | 메시지 식별·같은 시각의 정렬 보조 키 | 유지 |
| `chat_room_id` | 기록 조회·소유권으로 제한된 메모리 복원 | 유지, 현재 NOT NULL 유지 |
| `sender` | USER/BOT/INFO 분기, 모델 역할 변환 | 유지, NOT NULL 필요. INFO 제거는 아래 별도 전환 |
| `content` | 암호화된 질문·답변·출처 마크다운 | 타입/암호화 개선. LONGTEXT와 명시적 byte 한도를 검토하고 빈 값/NULL 금지 |
| `timestamp` | API·메모리·preview 정렬 | 유지·이름 명확화 가능. UTC/NOT NULL, `(timestamp,id)` 정렬 일치 필요 |

Base64 암호문은 평문보다 커진다. 현재 `TEXT`의 65,535byte 한도는 UTF-8 평문 약 48KiB 수준에서 넘을 수 있다. 근거 2MiB를 허용해도 전체 내용을 다시 저장하는 INFO가 이 한도를 만족하는 것은 아니다. 실제 MySQL에서 한글·긴 답변·큰 근거의 암호화 후 크기 경계 테스트가 필요하다.

`EncryptUtil`은 고정 키와 모드를 명시하지 않은 AES 호출을 사용한다. 환경/키 관리와 인증된 암호화 형식, 키 버전·nonce/tag가 포함된 envelope를 검토해야 한다. 예전 암호문이 있다면 새 키로 바로 복호화할 수 있다고 가정하지 않는다.

**INFO는 저장 제거 후보**다. 이미 방 스냅샷에 동일한 ID·제목·URL·본문이 있고 Advisor에서도 INFO를 제외한다. 최종적으로 스냅샷에서 응답 시 렌더링하면 중복·큰 메시지 저장을 줄일 수 있다. 현재 API가 USER/BOT/INFO를 반환하므로 응답 호환 방식을 먼저 정하고 enum·행을 정리한다. 즉시 삭제하지 않는다.

현재 Repository는 timestamp만 정렬하고 메모리는 추가로 ID 정렬한다. API·preview도 일치시킨다. 재시도 중복 방지와 턴 단위 순서는 향후 `request_id`·턴/실행 식별자 계약으로 해결하며 timestamp를 고유 키로 쓰지 않는다.

근거: [메시지 매핑](../../src/main/java/com/MoleLaw_backend/chat/entity/Message.java), [메모리](../../src/main/java/com/MoleLaw_backend/chat/service/memory/PersistedChatMemory.java), [렌더러](../../src/main/java/com/MoleLaw_backend/chat/service/answer/EvidenceRenderer.java), [암호화](../../src/main/java/com/MoleLaw_backend/chat/util/EncryptUtil.java).

## 4. chat_evidence_snapshot

| 속성 | 현재 실제 사용 | 판정·권장안 |
| --- | --- | --- |
| `room_id` | 방당 최초 근거 한 건·FK·삭제 cascade | 유지 |
| `evidence_json` | 최초/후속 동일 근거 본문 복원 | 유지. 원문 FK만 남기면 나중의 원문 변경/삭제에 영향을 받으므로 사본 필요 |
| `content_hash` | 복원 시 JSON SHA-256 검사 | 유지. 암호학적 서명·외부 원문 진위 보증으로 해석하지 않음 |
| `captured_at` | 스냅샷 저장 시각 | 유지. 법령 시행일·실제 외부 조회 시각과 구분 |
| `newEntity` | DB 컬럼 아님. 할당 ID의 Persistable 신규 판별 | 유지. 제거하면 assigned-ID merge 문제가 재발할 수 있음 |

현재 JSON에는 ID·kind·sourceId·제목·URL·본문만 있다. 조회/법령 버전·시행 기준일과 모델/프롬프트/색인 버전은 빠져 있다. 향후 `schema_version`과 필요한 provenance를 추가하되 현재 값만으로 검증하지 않은 날짜를 만들어 채우지 않는다. 새 Qdrant 근거는 `law-chunk:DB ID`와 다른 식별 의미를 갖기 때문에 허용 kind/sourceId 계약도 함께 확장한다.

## 5. law / law_chunk / law_embedding

현재 `LegalEvidenceCollector` → `LawSimilarityService`가 이 세 테이블을 사용한다. 새 Qdrant Tool은 등록돼 있지만 실제 최초 상담 수집기로 연결되지 않았다.

| 테이블·속성 | 실제 의미 | 판정·권장안 |
| --- | --- | --- |
| `law.id` | 구 청크 FK용 내부 PK | 전환까지 유지 |
| `law.name` | 제목·판례 검색·출처 안내 | 메타데이터로 유지, 버전 식별 키로 사용하지 않음 |
| `law.law_code` | API 기본정보의 **법령ID(LID)** | 이름 명확화. MST 아님. 버전 정보 없이 기존 행 재사용하는 구조 변경 필요 |
| `law.department` | 저장되는 부처명, 이후 업무 조회는 없음 | 보관 목적 재결정. 필요하면 공식 코드/명칭을 새 버전 메타데이터에 정의 |
| `law.created_at` | DB 행 생성 시각 | 유지 시 의미 명확화. 시행일·공포일·수집일 아님 |
| `law_chunk.id/law_id` | 구 검색 및 근거 원문 연결 | 전환까지 유지 |
| `article_number` | 현재 조문번호만 저장, 가지번호 누락 | 조문/가지번호 또는 명시적 원문 경로로 변경 |
| `clause_number` | 이름은 항이지만 실제 값은 호번호/호-목번호 | 그대로 재사용 금지. 항·호·목 경로와 분리하거나 신규 청크 모델로 대체 |
| `chunk_index` | 실제 저장 0/1/2가 조문/호/목 구분 | 진짜 분할 청크 순번과 다름. 새 청커 인덱스로 의미를 덮어쓰지 않음 |
| `content_text` | 현재 실제 답변 근거 | 전환까지 유지. 본문 해시/원문 버전 연결 필요 |
| `law_chunk.created_at` | 청크 생성 시각 | 감사 메타데이터, 본문 갱신·색인 시각과 다름 |
| `law_embedding.id/law_chunk_id` | 구 벡터 행·청크당 unique FK | 전환까지 유지 후 삭제 |
| `embedding_vector` | DataOutputStream float 직렬화 BLOB | Qdrant 전환 후 MySQL 중복 벡터 보관 제거 |
| `model_name` | 모델 이름만 보관 | 구 경로 존속 중에는 차원·모델/본문 해시 비교와 무효화 필요. 이름만으로 동질성 보장 불가 |
| `law_embedding.created_at` | 구 벡터 생성 시각 | 시행/원문 조회 시각 아님. 새 색인 상태와 구분 |

`LawSearchService`는 이름+LID로 재사용하지만 DDL unique가 없고 MST·시행일이 없어 개정본을 같은 청크에 갱신할 수 있다. 본문만 갱신해도 벡터는 자동 교체되지 않는다. `LawEmbeddingService`는 모델이 달라도 `existsByLawChunk`에서 건너뛸 수 있고, `LawSimilarityService`는 모델·차원·본문 해시 조건 없이 전체 BLOB을 읽는다. 잘못된 기존 식별 키에 unique만 추가하는 방식으로 해결하지 않는다.

근거: [구 수집·청크 저장](../../src/main/java/com/MoleLaw_backend/law/service/search/LawSearchService.java), 구 LawSimilarityService(삭제됨; Git 이력 참고), 구 LawEmbeddingService(삭제됨; Git 이력 참고).

## 6. legal_source

| 속성 | 현재 실제 의미 | 판정·권장안 |
| --- | --- | --- |
| `id` | 수집별 불변 UUID | 유지. 신규 근거/색인의 원문 참조 |
| `source_type` | LAW/CASE | 유지·허용 값 일치 검증 |
| `external_id` | LAW는 MST, CASE는 판례 일련번호 | 유지. 범용 이름의 종류별 의미 문서화 |
| `law_id` | LAW의 법령ID(LID), CASE는 NULL 가능 | 유지. 외부 ID와 혼동 금지 |
| `effective_date` | 외부 API 시행일 문자열 | 유지. 날짜 검증·DATE 전환 여부 결정. 수집일 아님 |
| `source_url` | 확인할 공식 출처 | 유지. 모델이 URL을 결정하지 않음 |
| `collected_at` | 실제 수집 스냅샷 시각 | 유지 |
| `content_hash` | 직렬화 JSON의 SHA-256 | 유지. 전역 UNIQUE 금지: 같은 원문을 재수집한 별도 스냅샷이 허용됨 |
| `raw_json` | 수집 당시 원문 JSON | 유지. 해시는 구조 정규화된 canonical JSON을 보장하지 않음 |

조회용 `(source_type, external_id, collected_at, id)` 인덱스를 검토한다. 원문 내용 중복과 수집 이력은 다른 책임이다. 별도 색인 기록에서 source UUID·내용/설정 fingerprint·collection·model revision·차원·generation·완료 상태를 관리해야 구버전/부분 적재 노출을 통제할 수 있다. 모든 상태를 원문 행이나 방 행에 추가하지 않는다.

## 적용 순서와 검증

1. 메시지 용량·명시적 한도, 사용자/방/메시지 필수 제약을 설계한다. nullable/중복/큰 암호문과 입력 검증 실패를 실제 MySQL에서 확인한다.
2. 공급자 subject 식별과 OAuth 단일 생성 경로, 암호화 envelope를 각각 기능 단위로 전환한다. 계정 삭제 cascade·복호화·재인증 회귀를 검증한다.
3. 새 원문/버전·색인 상태를 기준으로 Qdrant 검색과 판단 계약을 최초 상담에 연결한다. 로컬 임베딩은 동시성·유한 대기열로 제어하고 LLM 토큰은 실제 usage 기반으로 별도 정산한다.
4. 신규 원문 UUID·MST·조문 경로를 최초 방 스냅샷에 고정한다. 빈 DB 최초/후속 상담·버전 변경·중간 실패·삭제·재시도를 검증한다.
5. 구 실행 경로·관리용 API를 전환한 후 새 migration에서 `law_embedding → law_chunk → law` 제거 여부를 확정한다. INFO는 API 호환 렌더링을 마련한 뒤 중복 저장을 제거한다.

현재 코드가 사용하는 테이블/필드를 먼저 지우지 않는다. 데이터 없이 새로 시작한다는 기존 원칙과 별개로 적용된 V1/V2는 수정하지 않고 후속 Flyway migration을 추가한다. 실제 DB/볼륨 초기화는 이번 검토 범위가 아니다. SSE 상태 알림도 후속 추가사항이다.

## 사용자 모델 적용 후속 (2026-10-21)

위 표는 검토 당시 기준이다. 후속 구현에서 HTTP·서비스 Bean Validation, 이메일 정규화, password_hash 명명, OAuth provider_subject 식별·단일 생성 경로, V3 사용자 필수값·자격증명 CHECK·고유 제약을 적용했다. 상세 계약은 [사용자 계정·입력 검증](../auth/user-account-validation.md), 실제 로컬 DB 재생성 결과는 [MySQL 실행 가이드](../database/mysql-setup.md)를 따른다. 상담방·메시지 암호화 형식 및 법령 색인 모델 개편은 여전히 후속이다.
