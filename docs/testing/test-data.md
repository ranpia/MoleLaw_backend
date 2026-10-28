# 첫 조회 부하 실습용 합성 데이터

기준일: 2026-10-28. 데이터셋 ID: `lt-read-v1`. 전용 MySQL `molelaw_loadtest`에 생성·검증했다.
현재 자료는 일반 조회 API용이다. 실제 법령·판례·개인정보·AI 생성 답변을 포함하지 않으며 RAG 품질·성능 자료가 아니다.

## 준비한 규모

| 항목 | 수량 | 내용 |
| --- | --- | --- |
| 사용자 | 20명 | `lt-read-v1-u0001@example.invalid` ~ `lt-read-v1-u0020@example.invalid`, local 계정 |
| 상담방 | 200개 | 사용자당 10개, 제목에 합성 데이터셋 표시 |
| 저장 메시지 | 4,000개 | 방당 USER 10개·BOT 10개, 길이가 다른 한국어 합성 문장 |
| 고정 근거 스냅샷 | 200개 | 방당 합성 LAW_CHUNK 1개. 실제 법령 아님을 명시하고 example.invalid 주소 사용 |
| 조회 응답의 INFO | 방당 1개 | 스냅샷에서 렌더링하는 응답 항목이며 저장 메시지 수에는 포함하지 않음 |

비밀번호는 BCrypt `$2a$`·strength 10, 메시지는 앱의 AES-256-GCM `gcm1.loadtest-v1` 형식으로 저장했다.
스냅샷은 schemaVersion 2와 UTF-8 JSON의 SHA-256을 사용한다. 실제 로그인·복호화·근거 렌더링 API로 호환성을 확인했다.
자동으로 생성하는 가짜 사용자이며 가입/AI 상담 생성의 처리 성능을 시험한 것은 아니다.

## 로그인과 로컬 파일

- 합성 계정 비밀번호: Git 제외된 `docker/loadtest/.env`의 `LT_DATA_PASSWORD`. 실제 계정 비밀번호를 재사용하지 않는다.
- API 기본 주소: `http://localhost:18080`.
- 사용자 ID·소유 방 ID·메시지 식별 문자열: Git 제외된 `build/loadtest-data/manifest.json`.
- manifest에는 비밀번호·토큰·쿠키·암호화 키를 저장하지 않는다. 실제 비밀값은 `.env`에서만 읽는다.
- Access는 5분이므로 Locust 실행 시 로그인해 새 세션을 만든다. 준비 단계의 세션을 재사용하지 않는다.

## 다시 준비하는 명령

먼저 [전용 최소 환경](../../docker/loadtest/README.md)을 켜고 `.env`에 32~72바이트의 무작위 `LT_DATA_PASSWORD`를 설정한다.
현재 PC에는 이미 생성해 두었으므로 값을 교체하지 않는다. 새 환경은 다른 테스트 전용 값을 사용한다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile data build seed-data
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile data run --rm --no-deps seed-data
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/load-testing/check-data.ps1
```

도구는 앱의 새 endpoint나 상시 실행 코드가 아닌 일회성 Python 컨테이너다. 외부 호출 없이 mysql:3306의 고정 DB·계정만 사용한다.
Flyway V8, 전용 DB 확인값과 데이터셋 잠금을 검사한 뒤 한 트랜잭션으로 적재한다.
재실행은 기존 계정·방·전체 메시지·스냅샷을 대조하고 재사용한다. 비밀번호 초기화·기존 행 덮어쓰기·삭제는 하지 않는다.
내용·키·규모가 달라졌으면 중단한다. 데이터가 변경된 상태를 초기 상태로 조용히 간주하지 않는다.
향후 스키마·암호화 계약이 바뀌면 도구도 검토해야 하며 규모 확장은 새 데이터셋 버전으로 진행한다.

`gradlew clean`은 로컬 manifest를 지우지만 DB 볼륨은 지우지 않는다. 같은 비밀번호·암호화 키로 seed-data를 다시 실행하면 manifest를 복구할 수 있다.
데이터를 삭제하는 명령은 기본 실습 절차에 포함하지 않는다.

## 검증한 내용과 한계

최초 실행에서 20명·200방·4,000메시지·200스냅샷을 생성했다. 재실행은 추가 생성 0건이며 전체 저장 메시지의 복호화 내용과 스냅샷을 대조했다.
API 점검은 20개 계정의 로그인·본인 프로필·소유 방 10개를 확인하고, 사용자별 첫째/마지막 방 총 40개의 이력(저장 메시지 800개)을 표본 검증했다.
USER/BOT 순서·한국어 복호화·INFO 렌더링·타인 방 403을 확인하고 점검 세션은 로그아웃했다.

이는 순차 기능 확인이다. 동시 부하·운영 데이터 규모·대화 생성·법률 근거 정확도를 검증하지 않았다.
데이터 준비/로그인 점검도 Grafana 지표에 나타나므로 실제 부하 측정에서는 워밍업과 측정 시간을 별도로 잡는다.
이 데이터를 읽는 [Locust 사용자 1명 실습](first-load-test.md)을 이후 완료했다.
