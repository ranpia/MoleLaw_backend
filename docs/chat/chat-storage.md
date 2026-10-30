# 상담 저장·암호화·조회 계약

## 필수값과 크기

상담방은 소유 사용자, 공백이 아닌 1–255 Unicode 문자 제목, UTC `created_at`을 요구합니다. 모델의 제목도 방 생성 전에 검증합니다. 메시지는 방, `USER` 또는 `BOT`, 비어 있지 않은 암호문, UTC `timestamp`를 요구합니다. JPA와 후속 Flyway migration에서 필수 제약을 일치시킵니다.

질문은 공백만으로 구성 불가·최대 2000 Unicode 코드포인트·UTF-8 32KiB입니다. HTTP Bean Validation에 더해 서비스에서 코드포인트와 byte 수를 검증하므로 직접 서비스 호출에도 적용됩니다. 모델 답변은 UTF-8 평문 최대 1MiB입니다. `ChatTurnService`는 질문·답변을 둘 다 검증한 후 저장합니다. 암호화 후 Base64가 늘어나므로 `message.content`는 `LONGTEXT`입니다. 스냅샷 JSON의 별도 2MiB 한도와 혼동하지 마세요.

## AES-GCM과 실행 환경

저장소 루트 `.env`의 `CHAT_ENCRYPTION_KEY`에 **무작위 32byte 키의 Base64**를 제공해야 합니다. 코드·이미지·커밋에 키를 넣지 마세요. `CHAT_ENCRYPTION_KEY_VERSION`은 기본 `v1`, 허용값은 영숫자·`_`·`-` 1–32자입니다. Spring 시작 시 키 누락·잘못된 길이를 거절합니다. application.yml이 `.env`를 가져오고 app.chat.encryption.key/key-version에 매핑합니다. EncryptUtil은 `@Value` 생성자 주입으로 키를 한 번 검증하여 인스턴스에 보관합니다. 정적 메서드·System.getenv 직접 조회는 사용하지 않습니다. 프로세스 환경변수로도 같은 이름을 설정할 수 있으며 Spring의 표준 우선순위에 따라 파일보다 우선합니다.

저장 형식은 `gcm1.<keyVersion>.<Base64 nonce>.<Base64 ciphertext+tag>`입니다. AES-256-GCM, 메시지별 난수 12byte nonce, 128bit 인증 태그를 사용하고 형식·키 버전을 AAD로 인증합니다. 변조·다른 키·알 수 없는 버전은 복호화 실패입니다. 고정 키 AES 암호문의 호환 복호화는 제공하지 않습니다. 데이터 없이 새로 시작하는 배포 기준이며 기존 암호문이 있는 환경에 새 키를 즉시 적용해서는 안 됩니다.

현재는 활성 키 한 개만 지원합니다. 버전은 복호화 가능 여부를 식별하며 자동 키 교체 기능이 아닙니다. 저장된 메시지를 보존하려면 해당 키·버전도 보존해야 합니다. test 프로필과 단위 테스트 fixture는 격리된 합성 키를 사용하며 로컬 .env의 실제 키를 암호화 테스트에 사용하지 않습니다.

설정 예시(키는 실제 생성한 값으로 대체):

```dotenv
CHAT_ENCRYPTION_KEY=<32바이트 난수의 Base64>
CHAT_ENCRYPTION_KEY_VERSION=v1
```

서비스와 Advisor 메모리는 주입받은 EncryptUtil로 암복호화하고 DTO는 이미 복호화된 본문만 받습니다. EncryptionConfigurationTest는 실제 application.yml과 임시 .env를 사용해 프로세스 환경변수 없이 설정이 주입되는지 확인합니다.

## INFO와 삭제

DB 메시지는 `USER/BOT`만 저장합니다. API의 기존 `INFO` 응답은 고정 근거 스냅샷을 렌더링하여 최초 BOT 바로 다음에 한 번 삽입하며, 시각은 그 BOT과 같습니다. 근거 본문을 별도 암호화 메시지로 중복 저장하지 않습니다. Advisor는 저장된 USER/BOT만 읽습니다.

방에서 큰 근거·메시지를 역방향으로 로딩하는 JPA 관계를 제거했습니다. 방 삭제는 DB FK `ON DELETE CASCADE`로 메시지와 근거를 삭제합니다. 사용자 삭제도 방·메시지·근거까지 함께 삭제합니다. JPA 자동 DDL을 사용하지 않는 환경에서는 반드시 해당 Flyway migration을 먼저 적용하세요.

개별 저장 근거는 `DELETE /api/chat-rooms/{roomId}/evidence/{evidenceId}`로 제거합니다. V8의 방별 제외 기록으로 최초 스냅샷과 해시는 보존하고, 추가 판례의 해당 방 참조는 삭제합니다. 목록·상세·다음 상담 맥락에서 빠지며 공유 원문은 보존합니다. 검색 중 `evidence` 이벤트는 화면 미리보기일 뿐 저장되지 않으며, 성공한 답변과 인용 근거만 기존 트랜잭션에서 저장합니다. [실시간 이벤트](initial-answer-stream.md)와 [근거 삭제 계약](room-evidence.md)을 따릅니다.

## 정렬·페이지 조회와 검증

메시지·미리보기는 `(timestamp ASC, id ASC)`를 사용합니다. 방 목록 `GET /api/chat-rooms?page=0&size=20`은 `(created_at DESC, id DESC)`이며 `page >= 0`, `1 <= size <= 100`입니다. 응답 JSON은 기존 방 배열을 유지합니다. `LocalDateTime` 응답의 시간대 의미는 UTC이며 오프셋을 추가하지 않습니다.

`EncryptUtilTest`, `ChatContentLimitsTest`, `ChatStorageServiceTest`, `RoomEvidenceIntegrationTest`는 변조·난수 nonce·큰 한글 암호문·경계·INFO 재구성·페이지 전달·H2 삭제 cascade를 검증합니다. 실제 MySQL 타입·FK·한도 검증은 `mysqlIntegrationTest`에서 별도로 수행합니다.

설정 주입 전환 검증(2026-10-22): 일반 테스트 287 개(실패 0, 선택 실행 1 개 건너뜀)와 bootJar 통과. 임시 .env만으로 실제 YAML·@Value 주입, 빈 키 기동 실패, 서로 다른 인스턴스 키 격리, 기존 암호문 envelope·변조 검사·큰 본문 복원을 확인했다. 로컬 .env는 Git 제외 상태다.

운영 키 교체·보존 정책은 [사용자 판단 D09](../decisions/README.md), 동시 턴·중단 복구는 [작업 계획 P05](../plans/README.md)에서 관리합니다.

후속 상담은 `caseSearchRequested`를 BOT에, 요청별 명시 동의를 USER의 `caseSearchConsent`에 저장한다. 동의 전 외부 판례 호출은 차단한다. 읽고 인용한 새 판례 참조는 USER/BOT과 같은 성공 트랜잭션으로 저장한다. 근거 조회는 메시지와 분리한 목록·본문 API를 사용한다. [후속 상담 계약](follow-up-consultation.md)을 참고한다.
