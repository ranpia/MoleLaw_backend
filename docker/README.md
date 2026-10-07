# 로컬 MySQL·Qdrant·Redis 환경

## 구성과 초기 자원 예산

Spring Boot는 IDE 또는 Gradle로 실행하고 MySQL·Qdrant·Redis는 `docker/compose.yml`로 실행한다. MySQL은 `8.4.11`, Qdrant는 `v1.19.1`, Redis는 `7.4.11-alpine`으로 고정한다. 기존 원격 DB 데이터를 자동으로 가져오지 않으며 MySQL은 빈 개발용 DB로 시작한다.

| 서비스 | CPU 상한 | 메모리 상한 | 설정 |
| --- | --- | --- | --- |
| MySQL | 2 CPU | 2 GiB | InnoDB 버퍼 풀 1 GiB, 연결 상한 100, 호스트 3307 → 컨테이너 3306 |
| Qdrant | 4 CPU | 6 GiB | 단일 노드, HTTP 6333·gRPC 6334 |
| Redis | 1 CPU | 256 MiB | 호스트 6379, maxmemory 128 MiB, noeviction, 영속화 없음 |

이 값은 초기 개발·검색 평가용 예산이며 용량 보장이 아니다. CPU·메모리는 사용량의 상한이고 시작 시 전부 예약하지 않는다. 같은 CPU를 두 컨테이너가 공유하므로 물리 코어 6개가 반드시 필요한 것은 아니다. 버퍼 풀은 실제 MySQL 메모리 사용의 일부이므로 전체 상한보다 작게 유지한다.

DB 전용 PC는 우선 4코어 이상·RAM 16 GB·SSD 여유 50 GB를 시작점으로 제안한다. IDE·JVM·브라우저까지 같은 PC에서 실행하거나 색인 규모를 늘릴 경우 6~8코어·RAM 32 GB·SSD 여유 100 GB 이상이 편하다. 실제 사양은 청크 수·벡터 차원·동시 색인량을 측정한 뒤 조정한다. 로컬 모델은 이 예산에 포함하지 않는다.

float32 벡터 원본 크기는 대략 `청크 수 × 차원 × 4바이트`다. 1,536차원 기준 10만 청크는 약 0.57 GiB, 100만 청크는 약 5.72 GiB이며 여기에 검색 인덱스·본문·메타데이터·색인 중 임시 사용량·스냅샷 공간이 추가된다. 벡터 원본 크기를 그대로 RAM 요구량으로 해석하지 않는다. 컬렉션의 메모리·디스크 저장 전략은 임베딩 모델 확정 후 별도로 정한다.

Docker Desktop은 Linux containers 모드로 실행한다. 기본 컨테이너 메모리 상한 합계가 8.25 GiB이므로 Docker/WSL VM에는 운영 여유까지 포함해 10~12 GiB 정도를 사용할 수 있게 설정하는 것을 제안한다. 호스트 RAM이 부족하면 `.env.compose`에서 MySQL 1 GiB·버퍼 풀 512M, Qdrant 3 GiB 등으로 줄여 소규모 자료부터 검증한다. 메모리 상한을 늘려도 VM이나 호스트 메모리가 자동으로 늘어나지는 않는다.

## 실행

루트에서 `docker` 폴더로 이동한 뒤 예제 파일을 복사한다. 기존 `.env.compose`가 있으면 덮어쓰지 않는다.

```powershell
Set-Location docker
Copy-Item .env.compose.example .env.compose
```

`.env.compose`에 서로 다른 MySQL 사용자·root 비밀번호와 Qdrant API 키, Redis 비밀번호를 입력한다. REDIS_PASSWORD는 32자 이상의 무작위 영문·숫자·밑줄·하이픈으로 설정한다. 실제 값은 Git에서 제외된다. Compose 전용 파일이므로 기존 애플리케이션 `.env`와 별도로 관리한다.

```powershell
docker compose --env-file .env.compose config --quiet
docker compose --env-file .env.compose up -d
docker compose --env-file .env.compose ps
docker compose --env-file .env.compose logs --tail 50 mysql qdrant redis
docker compose --env-file .env.compose stats --no-stream
```

MySQL은 사용자 계정으로 실제 DB에 `SELECT 1`을 실행하는 healthcheck가 있다. 초기화에 시간이 걸릴 수 있으므로 `healthy` 상태를 확인한다. Qdrant 이미지에 curl 등 검사 도구가 있다고 가정하지 않아 컨테이너 healthcheck는 넣지 않았다. `running`만으로 준비 완료를 판단하지 않고 호스트에서 아래 요청이 성공하는지 확인한다. API 키는 콘솔에 직접 입력하지 않고 입력 프롬프트를 사용한다.

```powershell
$qdrantKeyInput = Read-Host 'Qdrant API key' -AsSecureString
$qdrantCredential = [System.Net.NetworkCredential]::new('', $qdrantKeyInput)
Invoke-RestMethod -Uri 'http://127.0.0.1:6333/collections' -Headers @{ 'api-key' = $qdrantCredential.Password }
```

원격 PC나 포트를 변경했다면 URL도 변경한다. 키 인증과 HTTP API 확인이며 gRPC·임베딩·검색 품질 검증은 애플리케이션 연동 단계에서 별도로 수행한다. 컬렉션은 임베딩 모델·차원을 확정한 뒤 생성한다.

Windows에서는 `docker` 폴더에서 `.\verify.ps1`로 반복 검증할 수 있다. Compose 설정에서 키를 메모리로 읽으며 출력하지 않는다. 제한된 준비 상태 대기 후 MySQL 사용자 인증·쿼리, Redis 비인증 거부·인증 쓰기·TTL·삭제, Qdrant 키 없는 요청 거부, 임시 3차원 컬렉션의 저장·벡터 검색을 검증하고 임시 컬렉션을 삭제한다. 실제 법령 컬렉션이나 데이터는 변경하지 않는다.

```powershell
docker compose --env-file .env.compose down
```

Windows 실행 정책이 verify.ps1을 차단하면 프로세스에만 예외를 적용해 실행한다: `powershell -NoProfile -ExecutionPolicy Bypass -File .\verify.ps1`.

일반 `down`은 MySQL·Qdrant 볼륨을 보존한다. Redis는 재시작·재생성 시 인증 상태를 비운다. `down -v`는 DB·색인·스냅샷 볼륨까지 삭제하므로 데이터 초기화를 의도한 경우에만 사용한다. MySQL 초기화용 계정·비밀번호 변수는 빈 볼륨에서만 적용되므로 기존 볼륨의 비밀번호를 환경변수 변경만으로 바꿀 수 없다.

## 애플리케이션 연결 범위

애플리케이션 기본 프로필은 `mysql`이며 기본 MySQL 주소는 로컬 3307이다. MySQL `DataSource`에 `@Primary`를 지정하고 Qdrant는 별도 프로필의 `VectorStore`로 구성했다. 루트 `.env.example`을 `.env`로 복사해 OAuth·API 키·JWT 설정을 채우고, MySQL 비밀번호·Qdrant 키는 `docker/.env.compose`와 일치시킨다. 실제 비밀값은 커밋하지 않는다.

| 프로필 | 구성 |
| --- | --- |
| 기본 / `mysql` | MySQL 기본 데이터소스, 스키마 기본 `validate` |
| `qdrant` | Qdrant gRPC 클라이언트와 `VectorStore`; 애플리케이션에서는 `mysql,qdrant`로 사용 |
| `local` | `mysql` + `qdrant`, 개발 DB 스키마 `update`, 로컬 쿠키 설정 |
| `test` | H2, 더미 인증 설정, 모델 모킹; MySQL·Qdrant 구성 제외 |

루트에서 로컬 애플리케이션을 실행한다. 테스트를 제외한 실행에는 DB 컨테이너가 필요하다.

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
```

현재 로컬 테스트는 HTTP(`http://localhost:8080`)로 실행하며 SSL을 비활성화하고 COOKIE_SECURE=false를 제공한다. 현재 쿠키 설정은 OAuth만 이 값을 따르며 일반 로그인·가입·재발급은 true 고정이므로 인증 보안 전환에서 통일해야 한다. Google OAuth 콘솔에는 `http://localhost:8080/login/oauth2/code/google`을 리디렉션 URI로 등록한다. 외부 Google·모델 API 통신은 제공자의 HTTPS 주소를 사용한다.

Kakao OAuth는 기본·로컬·테스트 프로필에서 제외되어 `KAKAO_CLIENT_ID`와 `KAKAO_CLIENT_SECRET`을 비워둘 수 있다. 나중에 필요하면 두 값을 입력하고 `local,kakao` 프로필로 실행한다.

`mysql`의 스키마 기본값은 `validate`이고 `local`에서는 `update`를 사용한다. `JPA_DDL_AUTO`로 명시적으로 변경할 수 있다. Flyway 마이그레이션은 아직 구현 전이므로 빈 개발 DB의 첫 실행에는 `local`을 사용한다. 기존 서비스의 지연 로딩 접근을 보존하기 위해 MySQL의 Open-in-View는 유지한다. 서비스 트랜잭션 정리는 별도 단계다.

IDE에서 환경변수로 직접 연결을 덮어쓸 수도 있다.

| 환경변수 | 로컬 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3307/molelawdb?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` |
| `SPRING_DATASOURCE_USERNAME` | `.env.compose`의 `MYSQL_USER` 값 |
| `SPRING_DATASOURCE_PASSWORD` | `.env.compose`의 `MYSQL_PASSWORD` 값 |

MySQL Connector/J 버전은 Spring Boot BOM이 관리하도록 변경했다. 실제 MySQL 연결·인증 호환성은 별도 통합 검증 대상이다. `test` 프로필은 H2를 사용해 Docker와 실제 API 키 없이 전체 테스트를 실행할 수 있다.

Qdrant 주소는 HTTP `127.0.0.1:6333`, gRPC `127.0.0.1:6334`이며 연결에 같은 API 키를 사용한다. `QDRANT_HOST`·`QDRANT_GRPC_PORT`·`QDRANT_API_KEY`·`QDRANT_COLLECTION`·`QDRANT_USE_TLS`로 클라이언트를 구성한다. 시작 시 컬렉션을 자동 생성하거나 임베딩 API를 호출하지 않는다. 실제 색인 작업에서 모델·차원을 확정하고 컬렉션을 준비해야 한다. Qdrant 빈 구성만으로 기존 MySQL 임베딩 테이블 검색이 자동으로 전환되지는 않는다.

## Redis 인증 상태 저장소

Redis는 Access의 jti·로그인 세션 활성 상태를 위한 인프라다. 비밀번호 인증과 PING healthcheck를 적용하고 기본 바인딩은 127.0.0.1:6379다. 비밀번호는 프로세스 인자 대신 환경변수에서 읽어 컨테이너 tmpfs에 권한 600의 설정 파일로 작성한다. Docker 관리 권한이 있는 사용자는 환경변수를 볼 수 있으므로 실제 값이나 전체 compose config를 로그로 출력하지 않는다.

RDB·AOF를 모두 끄고 /data는 tmpfs로 둔다. Redis 재시작 후 기존 Access 상태가 없어지면 인증을 거절하고 MySQL의 정상 Refresh로 새 상태를 발급하도록 애플리케이션을 구현할 예정이다. 자동 키 축출로 활성 인증 상태가 사라지지 않도록 noeviction을 사용하며 메모리 부족으로 등록이 실패하면 발급을 실패 처리해야 한다. TTL과 서버 세션 폐기는 애플리케이션 단계에서 구현한다.

현재 Spring Redis 의존성·클라이언트·JWT 저장소는 아직 추가하지 않았다. 따라서 Compose Redis 기동이 현재 JWT 검증 동작을 변경하지 않는다. 연결 예정 값은 호스트 127.0.0.1, 포트 REDIS_PORT(기본 6379), 비밀번호 docker/.env.compose의 REDIS_PASSWORD다. LAN 사용 시 호스트와 방화벽을 맞추고 인증 명령도 평문 통신임을 고려한다.

기존 .env.compose가 있으면 덮어쓰지 말고 REDIS_PASSWORD를 추가한다. 설정 확인은 docker compose --env-file .env.compose config --quiet로 수행한다. ./verify.ps1은 임시 키만 사용하고 원래 인증 상태를 변경하지 않는다.

## 모델 호출 설정

Spring Boot 3.5·Java 17을 유지하며 Spring AI 1.0.9 BOM을 사용한다. 최초 답변·후속 답변·키워드 추출은 공통 `AiChatService`의 `ChatClient`를 사용하고 임베딩은 `EmbeddingModel`을 사용한다. 시스템 지시와 키워드 프롬프트는 `src/main/resources/prompts`에 있다.

`OPENAI_CHAT_MODEL`의 기본값은 기존 `gpt-4`, `OPENAI_EMBEDDING_MODEL`은 기존 `text-embedding-3-small`, `OPENAI_EMBEDDING_DIMENSIONS`는 1536이다. 모델이나 차원을 변경할 때는 두 설정과 Qdrant 컬렉션을 함께 검토하고 기존 임베딩을 재사용하지 않는다. Spring AI의 HTTP 시도는 최초 호출 포함 최대 2회이며 공통 RestClient 연결 타임아웃은 5초, 읽기 타임아웃은 30초다. `app.ai.connect-timeout-ms`·`app.ai.read-timeout-ms`로 조절할 수 있다. 이 값은 각 HTTP 호출의 제한으로 질문 처리 전체의 시간 예산과 구분한다. Qdrant gRPC 호출 기본 타임아웃은 5초다.

후속 상담은 기존 최초 assistant 답변과 현재 user 질문을 역할별 메시지로 전달하는 단계다. 저장된 원문 근거·MessageChatMemoryAdvisor 기반 채팅방 기록 전달, 답변용 one-shot 예시, 최초 조회 상태 관리는 후속 구현 범위다.

## 같은 공유기의 다른 PC 사용

DB를 실행할 PC에 이 Compose 파일과 비공개 `.env.compose`를 준비한다. 예를 들어 DB PC의 LAN IP가 `192.168.0.20`이면 해당 PC에서 `DB_BIND_ADDRESS=192.168.0.20`으로 설정하고 Compose를 실행한다. 기본값 `127.0.0.1`은 해당 PC에서만 접근 가능하다.

개발 PC에서는 JDBC 주소의 호스트와 Qdrant HTTP·gRPC 호스트를 `192.168.0.20`으로 바꾼다. 공유기의 DHCP 주소 예약을 권장하며, DB PC 방화벽에서 개발 PC IP의 TCP 3307·6333·6334·6379 접근만 허용한다. 공유기 포트 포워딩은 사용하지 않는다. 기본 구성은 신뢰하는 로컬망용 평문 연결이며 신뢰하지 않는 네트워크에서는 TLS 또는 VPN을 추가한다.

Compose 파일을 다른 PC로 복사해도 named volume 데이터는 따라가지 않는다. 기존 데이터를 옮길 때는 MySQL 논리 백업·복원과 Qdrant 스냅샷·복원을 별도로 수행하거나 원문에서 색인을 재생성한다. 같은 Docker 호스트에서 독립된 환경을 추가로 만들려면 프로젝트 이름(`-p`)과 호스트 포트를 모두 구분한다.

## 초기 구동 검증 결과

- 2026-10-07 Redis `7.4.11-alpine` 기동·healthy 확인, 비밀번호 없는 요청 거부와 인증 쓰기·TTL·삭제 검증 통과. MySQL 쿼리와 Qdrant 임시 벡터 검색도 함께 재검증했다. 인증 키 만료 후 거부·Refresh 복구는 애플리케이션 구현 후 검증 대상이다.

- MySQL `8.4.11` 이미지 기동, 사용자 계정의 `SELECT 1`과 버전 조회, `healthy` 상태 확인.
- Qdrant `v1.19.1` 기동, 키 없는 요청 거부, 인증된 임시 컬렉션 생성·벡터 저장·검색·삭제 확인.
- 실제 컨테이너 CPU·메모리 상한이 MySQL 2 CPU·2 GiB, Qdrant 4 CPU·6 GiB로 적용됨을 확인.
- 초기 빈 데이터 상태에서 MySQL 약 570 MiB, Qdrant 약 120 MiB를 사용했다. 색인 후 용량·성능 추정치로 사용하지 않는다.
- 최초 지정한 MySQL `8.4.12` 태그는 레지스트리에 없어 배포된 `8.4.11`로 수정했다. 호스트 3306 바인딩이 거부되어 기본 호스트 포트를 3307로 변경했다.
- 백엔드 JDBC 연결, gRPC 클라이언트 연동, 법령 색인 품질, 재기동 후 데이터 복원, 다른 PC에서의 LAN 접속은 별도 검증 대상이다.

## 참고 문서

- [Redis 공식 이미지 태그](https://github.com/docker-library/official-images/blob/master/library/redis): 고정한 Redis 이미지.
- [Redis 보안](https://redis.io/docs/latest/operate/oss_and_stack/management/security/): 비밀번호 인증과 네트워크 접근.
- [Redis 영속화](https://redis.io/docs/latest/operate/oss_and_stack/management/persistence/): RDB·AOF 정책.

- [Docker Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/): CPU·메모리·포트·볼륨 설정.
- [MySQL 8.4 릴리스 노트](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/): 서버 버전 변경 사항.
- [Qdrant 릴리스](https://github.com/qdrant/qdrant/releases): 고정한 서버 버전.
- [Qdrant 설정](https://qdrant.tech/documentation/operations/configuration/): 환경변수와 API 키 설정.
- [Qdrant 저장소](https://qdrant.tech/documentation/manage-data/storage/): 컬렉션의 메모리·디스크 저장 전략.
- [Spring AI 1.0 시작 가이드](https://docs.spring.io/spring-ai/reference/1.0/getting-started.html): Spring Boot 3.4·3.5 호환 범위와 BOM 구성.
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html): 역할별 메시지와 구조화 응답.
- [OpenAI 임베딩 가이드](https://developers.openai.com/api/docs/guides/embeddings): 기본 모델 차원과 차원 변경 옵션.
