# 부하 테스트 전용 Docker 환경

개발 환경과 별도인 `molelaw-loadtest` 프로젝트다. DB·Qdrant·모델 캐시·관측 데이터는 모두 새 전용 볼륨을 사용한다.
루트 `.env`·개발용 Compose·외부 볼륨을 가져오지 않는다. 최소 환경 실 기동·지표 연결을 확인했으며 부하 시나리오·합성 데이터·외부 API 대역은 미구현이다.

## 1. 환경변수 준비

저장소 루트 PowerShell 기준이다. 기존 파일이 있으면 덮어쓰지 않는다.

```powershell
if (Test-Path docker/loadtest/.env) {
    throw 'docker/loadtest/.env already exists. Edit it instead of overwriting.'
}
Copy-Item docker/loadtest/.env.example docker/loadtest/.env
```

`.env`의 빈 비밀값을 테스트 전용 무작위 값으로 채운다. 개발·운영 비밀번호와 API 키를 복사하지 않는다.
문자열 키는 아래처럼 32바이트를 hex로 만들 수 있다. CHAT_ENCRYPTION_KEY만 Base64 형식을 사용한다.
값은 로컬 파일에만 보관하고 터미널 출력·커밋에 넣지 않는다.

```powershell
$loadtestBytes = New-Object byte[] 32
$loadtestRng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$loadtestRng.GetBytes($loadtestBytes)
$loadtestHex = -join ($loadtestBytes | ForEach-Object { $_.ToString('x2') })
# $loadtestHex를 .env의 해당 항목에 사용한다. 키마다 새 난수를 생성한다.
$loadtestRng.GetBytes($loadtestBytes)
$loadtestBase64 = [Convert]::ToBase64String($loadtestBytes)
# $loadtestBase64는 LT_CHAT_ENCRYPTION_KEY에 사용한다.
$loadtestRng.Dispose()
```

실제 값이 출력되는 `docker compose config` 대신 아래 검증 명령을 사용한다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile rag --profile tools config --quiet
```

## 2. 백엔드 빌드

```powershell
$env:JAVA_HOME='C:\gen-ai-course\tools\jdk-17'
.\gradlew.bat test bootJar
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml build backend
```

`molelaw-app:loadtest`는 위에서 생성한 JAR를 사용한다. 소스 수정 뒤에는 bootJar와 이미지 빌드를 다시 수행한다.
빌드 커밋과 이미지 ID를 측정 결과에 기록한다. 개발 서버용 `molelaw-app:local` 이미지를 교체하지 않는다.

## 3. 최소 환경 시작

전체 RAG까지 동시에 실행하면 자원이 부족할 수 있다. 기존 개발 환경의 중지는 별도로 판단하고, 기존 볼륨은 보존한다.
아래 명령은 새 테스트 환경만 시작한다. OAuth·유료 모델은 더미 설정이며 실제 외부 호출은 막혀 있다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml up -d --build
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml ps
```

| 접속 | 주소 | 비고 |
| --- | --- | --- |
| 백엔드 | http://localhost:18080 | local,loadtest 프로필. local 그룹은 mysql만 활성화하도록 재정의. Swagger 비활성, 기존 인증·CSRF 유지 |
| Grafana | http://localhost:13000 | 사용자 loadtest, 비밀번호 LT_GRAFANA_PASSWORD |
| Prometheus | http://localhost:19090 | Targets에서 molelaw-backend가 UP인지 확인 |
| MySQL | 127.0.0.1:13307 | DB·계정 molelaw_loadtest |
| 관리 포트 | backend:9091 | Docker 내부 전용. 호스트 포트 매핑 없음 |

Grafana에 `MoleLaw Loadtest - Backend` 대시보드와 Prometheus 데이터소스가 자동 등록된다.
HTTP RPS·p95/p99·4xx/5xx·JVM 메모리·GC·Hikari active/pending을 표시한다.
요청 전 또는 표본이 적을 때 No data가 나타날 수 있다. RAG 내부·DB 자체·컨테이너 계측은 후속 작업이다.
Grafana 비밀번호는 빈 전용 볼륨 최초 시작 때 적용되므로 기존 볼륨의 비밀번호를 env 변경만으로 재설정할 수는 없다.

로그인 정보는 사용자 `loadtest`, 비밀번호는 로컬 `docker/loadtest/.env`의 `LT_GRAFANA_PASSWORD`다. 비밀번호는 실행 로그나 문서에 출력하지 않는다.

내부 전용 네트워크만으로는 현재 Docker에서 호스트 포트가 열리지 않아 HAProxy 게이트웨이가 위 포트를 전달한다.
게이트웨이는 지정 서비스에 대한 TCP 경로만 가지며 관리 포트 9091 경로는 없다. 백엔드는 외부 네트워크에 연결되지 않는다.
호스트에서 보낸 요청에는 게이트웨이 처리 비용도 포함된다. 부하 결과에 이 경로를 기록한다.
Qdrant 포트는 미리 예약되며 rag 미실행 시 연결할 대상이 없다. 선택 대상의 DNS 관련 경고는 최소 구성에서는 예상된다.

로컬 HTTP 쿠키의 기존 보호 정책을 따르기 위해 local 프로필을 사용하되, Swagger와 Swagger CSRF 자동 처리를 명시적으로 비활성화한다.
CSRF 쿠키는 HttpOnly를 유지하고 JSON의 토큰을 헤더로 전송한다. 운영 쿠키 정책이나 인증 코드는 완화하지 않았다.

기동 후 아래의 소규모 확인을 실행한다. 로그인이나 데이터 생성 없이 상태·보안 경계·지표 연결을 확인하며 부하 시험은 아니다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/load-testing/check-minimal.ps1
```

## 4. RAG를 추가하는 경우

`.env`에서 `LT_RAG_ENABLED=true`로 바꾸고 아래를 실행한다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile rag up -d --build
```

전용 Qdrant는 HTTP 16333·gRPC 16334, KURE는 21437이다. 새 캐시에 모델을 다운로드하므로 시간·디스크가 추가로 필요하다.
모델은 KURE 이미지의 고정 revision을 사용하며 임베딩과 리랭커가 실행 잠금과 대기열을 공유한다.
KURE의 다운로드 네트워크만 인터넷에 연결된다. 백엔드는 internal 네트워크에만 연결되어 유료 AI·OpenLaw·OAuth에 직접 나갈 수 없다.
지금은 외부 호출 대역이 없으므로 전체 최초·후속 상담 성공을 기대하면 안 된다. RAG 구성요소 준비와 전체 상담 대역 연결은 별개다.
컬렉션·합성 원문·사용자·상담 데이터 적재 및 준비 확인은 다음 단계에서 제공한다. 단순 컨테이너 기동은 검색 준비 완료가 아니다.
Spring AI Qdrant VectorStore 프로필은 활성화하지 않으며 현재 내부 법령 도구의 REST 클라이언트를 사용한다.

Locust는 현재 버전 확인만 실행하고 종료한다. UI·시나리오·자동 부하는 시작하지 않는다.

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile tools run --rm --no-deps locust
```

## 5. 종료와 보존

```powershell
docker compose --env-file docker/loadtest/.env -f docker/loadtest/compose.yml --profile rag --profile tools down
```

`down`은 테스트 컨테이너와 네트워크만 제거하고 전용 볼륨은 보존한다. 데이터 삭제 옵션 `-v`는 기본 절차에 포함하지 않는다.
Redis는 tmpfs이므로 컨테이너 재생성 시 Refresh 상태가 사라져 테스트 사용자 재로그인이 필요하다.

## 검증 범위

구성 검증은 Compose 해석·볼륨/네트워크 격리·관리 포트 미공개를 확인한다.
백엔드 HTTP 테스트는 별도 관리 포트에서 scrape 성공·JVM/Hikari/HTTP histogram 존재와 애플리케이션 포트의 인증·CSRF 유지를 확인한다.
2026-10-28 최소 환경에서 MySQL Flyway V8, backend healthy, Prometheus backend UP, JVM/Hikari/HTTP histogram 존재,
Grafana 대시보드 등록·데이터소스 health OK를 확인했다. 기본 경로의 익명 인증 차단·CSRF·Swagger 차단 및 CSRF 쿠키 HttpOnly도 확인했다.
모델 다운로드·실 RAG·실 사용자 기능·부하 검증은 별도 진행해야 한다.
기본 배포 프로필은 관리 HTTP 포트와 endpoint 접근이 비활성화된다. loadtest 프로필을 운영에 활성화하지 않는다.

참고: [Grafana Docker](https://grafana.com/docs/grafana/latest/setup-grafana/installation/docker/),
[Grafana provisioning](https://grafana.com/docs/grafana/latest/administration/provisioning/),
[Prometheus 설정](https://prometheus.io/docs/prometheus/latest/configuration/configuration/).
