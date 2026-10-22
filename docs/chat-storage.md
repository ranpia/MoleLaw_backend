# 상담 저장·암호화·조회 계약

## 필수값과 크기

상담방은 소유 사용자, 공백이 아닌 1–255 Unicode 문자 제목, UTC `created_at`을 요구합니다. 모델의 제목도 방 생성 전에 검증합니다. 메시지는 방, `USER` 또는 `BOT`, 비어 있지 않은 암호문, UTC `timestamp`를 요구합니다. JPA와 후속 Flyway migration에서 필수 제약을 일치시킵니다.

질문은 공백만으로 구성 불가·최대 2000 Unicode 코드포인트·UTF-8 32KiB입니다. HTTP Bean Validation에 더해 서비스에서 코드포인트와 byte 수를 검증하므로 직접 서비스 호출에도 적용됩니다. 모델 답변은 UTF-8 평문 최대 1MiB입니다. `ChatTurnService`는 질문·답변을 둘 다 검증한 후 저장합니다. 암호화 후 Base64가 늘어나므로 `message.content`는 `LONGTEXT`입니다. 스냅샷 JSON의 별도 2MiB 한도와 혼동하지 마세요.

## AES-GCM과 실행 환경

프로세스 환경변수 `CHAT_ENCRYPTION_KEY`에 **무작위 32byte 키의 Base64**를 제공해야 합니다. 코드·이미지·커밋에 키를 넣지 마세요. `CHAT_ENCRYPTION_KEY_VERSION`은 기본 `v1`, 허용값은 영숫자·`_`·`-` 1–32자입니다. Spring 시작 시 키 누락·잘못된 길이를 거절합니다. 이 암호화 유틸리티는 실제 **프로세스 환경변수**를 읽으므로 저장소 `.env` 파일이나 Spring 속성만 작성해서는 충분하지 않습니다. IDE 실행 환경, 배포 Secret 또는 컨테이너 환경으로 전달하세요.

저장 형식은 `gcm1.<keyVersion>.<Base64 nonce>.<Base64 ciphertext+tag>`입니다. AES-256-GCM, 메시지별 난수 12byte nonce, 128bit 인증 태그를 사용하고 형식·키 버전을 AAD로 인증합니다. 변조·다른 키·알 수 없는 버전은 복호화 실패입니다. 고정 키 AES 암호문의 호환 복호화는 제공하지 않습니다. 데이터 없이 새로 시작하는 배포 기준이며 기존 암호문이 있는 환경에 새 키를 즉시 적용해서는 안 됩니다.

현재는 활성 키 한 개만 지원합니다. 버전은 복호화 가능 여부를 식별하며 자동 키 교체 기능이 아닙니다. 저장된 메시지를 보존하려면 해당 키·버전도 보존해야 합니다. Gradle 테스트는 격리된 합성 테스트 키를 주입하며 운영 키를 사용하지 않습니다.

## INFO와 삭제

DB 메시지는 `USER/BOT`만 저장합니다. API의 기존 `INFO` 응답은 고정 근거 스냅샷을 렌더링하여 최초 BOT 바로 다음에 한 번 삽입하며, 시각은 그 BOT과 같습니다. 근거 본문을 별도 암호화 메시지로 중복 저장하지 않습니다. Advisor는 저장된 USER/BOT만 읽습니다.

방에서 큰 근거·메시지를 역방향으로 로딩하는 JPA 관계를 제거했습니다. 방 삭제는 DB FK `ON DELETE CASCADE`로 메시지와 근거를 삭제합니다. 사용자 삭제도 방·메시지·근거까지 함께 삭제합니다. JPA 자동 DDL을 사용하지 않는 환경에서는 반드시 해당 Flyway migration을 먼저 적용하세요.

## 정렬·페이지 조회와 검증

메시지·미리보기는 `(timestamp ASC, id ASC)`를 사용합니다. 방 목록 `GET /api/chat-rooms?page=0&size=20`은 `(created_at DESC, id DESC)`이며 `page >= 0`, `1 <= size <= 100`입니다. 응답 JSON은 기존 방 배열을 유지합니다. `LocalDateTime` 응답의 시간대 의미는 UTC이며 오프셋을 추가하지 않습니다.

`EncryptUtilTest`, `ChatContentLimitsTest`, `ChatStorageServiceTest`, `RoomEvidenceIntegrationTest`는 변조·난수 nonce·큰 한글 암호문·경계·INFO 재구성·페이지 전달·H2 삭제 cascade를 검증합니다. 실제 MySQL 타입·FK·한도 검증은 `mysqlIntegrationTest`에서 별도로 수행합니다.
