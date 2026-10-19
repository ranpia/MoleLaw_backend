# Docker 실행 가이드

Docker 관련 구성은 이 디렉터리에 모은다. Spring 애플리케이션 환경변수는 루트 `.env.example`, 프로젝트 구조와 실행은 [루트 README](../README.md)를 참고한다.

법령 검색 실험은 실행 중인 로컬 Qdrant에 별도 실험 컬렉션을 사용한다. Gemma·BGE-M3·KURE-v1의 300·500·700문자 자료를 총 9개 컬렉션, 63,486개 포인트로 적재했다. [Qdrant 실험 안내](../evaluation/legal-retrieval/reports/qdrant-lab/README.md)에서 대시보드와 적재·검색·재색인·삭제 명령을 확인할 수 있다. 별도 Qdrant 이미지나 컨테이너는 추가하지 않는다.

| 파일 | 용도 |
| --- | --- |
| `app.Dockerfile` | Gradle로 빌드한 Spring Boot JAR의 배포 이미지 |
| `compose.yml` | 로컬 MySQL·Qdrant·Redis |
| `.env.compose.example` | Compose 환경변수 예제 |
| `compose.insight.yml` | 선택적으로 실행하는 Redis Insight |
| `verify.ps1` | 실행 중인 DB의 인증·읽기·쓰기 검증 |
| [ollama/](ollama/README.md) | 애플리케이션과 독립된 CPU 임베딩 실험 |

## 로컬 DB 실행

Docker Desktop을 Linux containers 모드로 실행한다. 저장소 루트에서 다음을 수행한다. 기존 `.env.compose`가 있으면 복사하지 않는다.

```powershell
Copy-Item docker/.env.compose.example docker/.env.compose
```

`docker/.env.compose`에 MySQL 사용자·root 비밀번호, Qdrant API 키, Redis 비밀번호를 입력한다. Redis 비밀번호는 영문·숫자·밑줄·하이픈으로 구성한 32자 이상의 무작위 값을 사용한다. 이 파일은 Git에서 제외된다. 실제 비밀값을 포함한 `compose config` 전체 결과는 출력하지 않는다.

```powershell
docker compose --env-file docker/.env.compose -f docker/compose.yml config --quiet
docker compose --env-file docker/.env.compose -f docker/compose.yml up -d
docker compose --env-file docker/.env.compose -f docker/compose.yml ps
powershell -NoProfile -ExecutionPolicy Bypass -File docker/verify.ps1
```

검증 스크립트는 준비 상태를 기다린 뒤 MySQL 인증과 `SELECT 1`, Redis 비인증 거부·임시 키 쓰기·TTL·삭제, Qdrant API 키 검사·임시 컬렉션 벡터 검색을 검증한다. 임시 데이터만 정리한다. 실제 법령 색인이나 애플리케이션 연동 검증은 별도다.

| 서비스 | 고정 이미지 | 기본 호스트 포트 | CPU / 메모리 상한 |
| --- | --- | --- | --- |
| MySQL | `mysql:8.4.11` | `3307` | 2 / 2 GiB |
| Qdrant | `qdrant/qdrant:v1.19.1` | HTTP `6333`, gRPC `6334` | 4 / 6 GiB |
| Redis | `redis:7.4.11-alpine` | `6379` | 1 / 256 MiB |

기본 바인딩은 `127.0.0.1`이다. 자원 값은 예약량이 아닌 상한이며 `.env.compose`에서 조정한다. 기본 메모리 상한 합계는 8.25 GiB이므로 Docker VM에 운영 여유를 포함한 메모리를 배정한다.

MySQL과 Qdrant 데이터는 named volume에 보존된다. Redis는 RDB·AOF를 끄고 tmpfs를 사용한다. Refresh 회전 상태를 저장하므로 재시작해 상태가 유실되면 재로그인이 필요하다. 기존 stateless Access는 만료까지 최대 5분 유효하다. 이 로컬 비영속 설정을 운영 Refresh 복구 정책으로 사용하지 않는다. Redis `maxmemory`는 128 MiB, 정책은 `noeviction`이다.

Refresh 회전은 `molelaw:auth:refresh:v1:*` 키의 해시·세대·절대 TTL로 관리하며 JWT Refresh는 더 이상 받지 않는다. Redis Insight에서 해당 패턴으로 확인할 수 있다. 루트의 `.\gradlew.bat redisIntegrationTest`는 별도 임시 Redis 컨테이너에서 동시 소비·재사용/폐기·TTL·키 유실·메모리 부족/연결 오류를 검증하며 기존 개발 Redis를 변경하지 않는다. [현재 계약과 실행 가이드](../docs/refresh-rotation.md)를 따른다.

```powershell
# 컨테이너만 중지·제거하고 DB 볼륨은 보존한다.
docker compose --env-file docker/.env.compose -f docker/compose.yml down
```

## 애플리케이션 연결

Spring은 IDE 또는 Gradle로 실행하고, 루트 `.env`에 다음 연결 정보를 설정한다. Compose의 비밀번호·API 키와 일치시켜야 한다.

| 환경변수 | 로컬 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3307/molelawdb?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` |
| `SPRING_DATASOURCE_USERNAME` | Compose의 `MYSQL_USER` |
| `SPRING_DATASOURCE_PASSWORD` | Compose의 `MYSQL_PASSWORD` |
| `QDRANT_HOST` | `127.0.0.1` |
| `QDRANT_GRPC_PORT` | `6334` |
| `QDRANT_API_KEY` | Compose의 `QDRANT_API_KEY` |
| `QDRANT_USE_TLS` | `false` |
| `REDIS_HOST` | `127.0.0.1` |
| `REDIS_PORT` | `6379` |
| `REDIS_PASSWORD` | Compose의 `REDIS_PASSWORD` |
| `REDIS_SSL_ENABLED` | `false` |

빈 MySQL DB는 애플리케이션 시작 시 Flyway V1이 초기화하고 기본/mysql·local 모두 JPA `validate`로 검사한다. 기존 데이터는 이관하지 않는다. 이력 없는 비어 있지 않은 DB는 자동 baseline 없이 거절한다. 수동 `001-legal-source.sql`과 Hibernate `update`를 함께 사용하지 않는다. `JPA_DDL_AUTO`를 설정했다면 `validate`로 맞춘다. [MySQL 실행 가이드](../docs/mysql-setup.md)를 따른다. Qdrant 컬렉션과 법령 데이터는 Compose가 생성하지 않는다. H2 기반 `test` 프로필은 이 인프라 없이 실행된다.

다른 PC에서 연결할 때는 DB PC의 LAN IP를 `DB_BIND_ADDRESS`와 애플리케이션 호스트에 설정하고 방화벽에서 개발 PC의 접근만 허용한다. MySQL·Qdrant 볼륨은 Compose 파일을 복사해도 이동하지 않는다. 데이터 이동에는 별도 백업·복원이 필요하다.

## Redis Insight

`docker/.env.compose`의 `REDISINSIGHT_ENCRYPTION_KEY`에 별도 무작위 키를 설정하고 재시작 후에도 유지한다.

```powershell
docker compose --env-file docker/.env.compose -f docker/compose.yml -f docker/compose.insight.yml config --quiet
docker compose --env-file docker/.env.compose -f docker/compose.yml -f docker/compose.insight.yml up -d redisinsight
```

`http://127.0.0.1:5540`에서 접속한다. 포트는 루프백으로 고정되며 UI 설정은 `redisinsight_data`에 보존된다. UI만 중지하려면 같은 Compose 옵션에 `stop redisinsight`를 사용한다.

## 애플리케이션 배포 이미지

저장소 루트를 빌드 컨텍스트로 사용한다. 루트 `.dockerignore`는 패키징한 JAR과 Dockerfile만 컨텍스트에 포함한다.

```powershell
.\gradlew.bat bootJar
docker build -f docker/app.Dockerfile -t molelaw-app:local .
```

이미지는 프로젝트와 동일한 Java 17 JRE를 사용한다. Gradle 버전이나 프로젝트 이름을 변경해 JAR 이름이 달라지면 `--build-arg JAR_FILE=build/libs/<파일명>.jar`를 지정한다. 기본 파일명은 `MoleLaw_backend-0.0.1-SNAPSHOT.jar`다. 실행 환경변수는 이미지에 포함하지 않고 배포 시 `--env-file`로 전달한다. 컨테이너에서 `127.0.0.1`은 컨테이너 자신이므로 DB 호스트는 배포 환경에 맞춰 설정한다.

수동 배포 workflow는 [deploy.yml](../.github/workflows/deploy.yml)에서 같은 Dockerfile을 사용한다. 운영에는 `local`·`test` 프로필을 지정하지 않는다.
