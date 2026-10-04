# 로컬 MySQL·Qdrant 환경

## 구성과 초기 자원 예산

Spring Boot는 IDE 또는 Gradle로 실행하고 DB 두 개는 `docker/compose.yml`로 실행한다. MySQL은 `8.4.11`, Qdrant는 `v1.19.1`로 고정한다. 기존 원격 DB 데이터를 자동으로 가져오지 않으며 MySQL은 빈 개발용 DB로 시작한다.

| 서비스 | CPU 상한 | 메모리 상한 | 설정 |
| --- | --- | --- | --- |
| MySQL | 2 CPU | 2 GiB | InnoDB 버퍼 풀 1 GiB, 연결 상한 100, 호스트 3307 → 컨테이너 3306 |
| Qdrant | 4 CPU | 6 GiB | 단일 노드, HTTP 6333·gRPC 6334 |

이 값은 초기 개발·검색 평가용 예산이며 용량 보장이 아니다. CPU·메모리는 사용량의 상한이고 시작 시 전부 예약하지 않는다. 같은 CPU를 두 컨테이너가 공유하므로 물리 코어 6개가 반드시 필요한 것은 아니다. 버퍼 풀은 실제 MySQL 메모리 사용의 일부이므로 전체 상한보다 작게 유지한다.

DB 전용 PC는 우선 4코어 이상·RAM 16 GB·SSD 여유 50 GB를 시작점으로 제안한다. IDE·JVM·브라우저까지 같은 PC에서 실행하거나 색인 규모를 늘릴 경우 6~8코어·RAM 32 GB·SSD 여유 100 GB 이상이 편하다. 실제 사양은 청크 수·벡터 차원·동시 색인량을 측정한 뒤 조정한다. 로컬 모델은 이 예산에 포함하지 않는다.

float32 벡터 원본 크기는 대략 `청크 수 × 차원 × 4바이트`다. 1,536차원 기준 10만 청크는 약 0.57 GiB, 100만 청크는 약 5.72 GiB이며 여기에 검색 인덱스·본문·메타데이터·색인 중 임시 사용량·스냅샷 공간이 추가된다. 벡터 원본 크기를 그대로 RAM 요구량으로 해석하지 않는다. 컬렉션의 메모리·디스크 저장 전략은 임베딩 모델 확정 후 별도로 정한다.

Docker Desktop은 Linux containers 모드로 실행한다. 기본 컨테이너 메모리 상한 합계가 8 GiB이므로 Docker/WSL VM에는 운영 여유까지 포함해 10~12 GiB 정도를 사용할 수 있게 설정하는 것을 제안한다. 호스트 RAM이 부족하면 `.env.compose`에서 MySQL 1 GiB·버퍼 풀 512M, Qdrant 3 GiB 등으로 줄여 소규모 자료부터 검증한다. 메모리 상한을 늘려도 VM이나 호스트 메모리가 자동으로 늘어나지는 않는다.

## 실행

루트에서 `docker` 폴더로 이동한 뒤 예제 파일을 복사한다. 기존 `.env.compose`가 있으면 덮어쓰지 않는다.

```powershell
Set-Location docker
Copy-Item .env.compose.example .env.compose
```

`.env.compose`에 서로 다른 MySQL 사용자·root 비밀번호와 Qdrant API 키를 입력한다. 실제 값은 Git에서 제외된다. Compose 전용 파일이므로 기존 애플리케이션 `.env`와 별도로 관리한다.

```powershell
docker compose --env-file .env.compose config --quiet
docker compose --env-file .env.compose up -d
docker compose --env-file .env.compose ps
docker compose --env-file .env.compose logs --tail 50 mysql qdrant
docker compose --env-file .env.compose stats --no-stream
```

MySQL은 사용자 계정으로 실제 DB에 `SELECT 1`을 실행하는 healthcheck가 있다. 초기화에 시간이 걸릴 수 있으므로 `healthy` 상태를 확인한다. Qdrant 이미지에 curl 등 검사 도구가 있다고 가정하지 않아 컨테이너 healthcheck는 넣지 않았다. `running`만으로 준비 완료를 판단하지 않고 호스트에서 아래 요청이 성공하는지 확인한다. API 키는 콘솔에 직접 입력하지 않고 입력 프롬프트를 사용한다.

```powershell
$qdrantKeyInput = Read-Host 'Qdrant API key' -AsSecureString
$qdrantCredential = [System.Net.NetworkCredential]::new('', $qdrantKeyInput)
Invoke-RestMethod -Uri 'http://127.0.0.1:6333/collections' -Headers @{ 'api-key' = $qdrantCredential.Password }
```

원격 PC나 포트를 변경했다면 URL도 변경한다. 키 인증과 HTTP API 확인이며 gRPC·임베딩·검색 품질 검증은 애플리케이션 연동 단계에서 별도로 수행한다. 컬렉션은 임베딩 모델·차원을 확정한 뒤 생성한다.

Windows에서는 `docker` 폴더에서 `.\verify.ps1`로 반복 검증할 수 있다. Compose 설정에서 키를 메모리로 읽으며 출력하지 않는다. 제한된 준비 상태 대기 후 MySQL 사용자 인증·쿼리, Qdrant 키 없는 요청 거부, 임시 3차원 컬렉션의 저장·벡터 검색을 검증하고 임시 컬렉션을 삭제한다. 실제 법령 컬렉션이나 데이터는 변경하지 않는다.

```powershell
docker compose --env-file .env.compose down
```

일반 `down`은 데이터를 보존한다. `down -v`는 DB·색인·스냅샷 볼륨까지 삭제하므로 데이터 초기화를 의도한 경우에만 사용한다. MySQL 초기화용 계정·비밀번호 변수는 빈 볼륨에서만 적용되므로 기존 볼륨의 비밀번호를 환경변수 변경만으로 바꿀 수 없다.

## 애플리케이션 연결 범위

이 변경은 DB 인프라 구성이다. 현재 기본 애플리케이션 설정은 원격 DB를 가리키므로 IDE 실행 환경변수로 다음 값을 명시해야 로컬 DB에 연결된다.

| 환경변수 | 로컬 값 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:mysql://127.0.0.1:3307/molelawdb?serverTimezone=Asia/Seoul&characterEncoding=UTF-8` |
| `SPRING_DATASOURCE_USERNAME` | `.env.compose`의 `MYSQL_USER` 값 |
| `SPRING_DATASOURCE_PASSWORD` | `.env.compose`의 `MYSQL_PASSWORD` 값 |

OAuth·모델 API·JWT 등 기존 실행 설정도 필요하다. 현재 `ddl-auto: update`는 이 빈 개발용 DB에만 적용한다. 명시적인 local/test 프로필과 Flyway 마이그레이션은 다음 단계에서 구성한다. 기존 MySQL Connector/J `8.0.33`의 8.4 서버 연결·인증 호환성은 애플리케이션 연결 테스트에서 확인하고 드라이버 갱신 여부를 결정한다.

Qdrant 주소는 HTTP `127.0.0.1:6333`, gRPC `127.0.0.1:6334`이며 연결에 같은 API 키를 사용한다. 현재 백엔드에는 Qdrant 연동이 없으므로 이 컨테이너만 실행해도 기존 임베딩 테이블 검색이 자동으로 전환되지는 않는다. Spring AI 연결은 별도 구현 단계다.

## 같은 공유기의 다른 PC 사용

DB를 실행할 PC에 이 Compose 파일과 비공개 `.env.compose`를 준비한다. 예를 들어 DB PC의 LAN IP가 `192.168.0.20`이면 해당 PC에서 `DB_BIND_ADDRESS=192.168.0.20`으로 설정하고 Compose를 실행한다. 기본값 `127.0.0.1`은 해당 PC에서만 접근 가능하다.

개발 PC에서는 JDBC 주소의 호스트와 Qdrant HTTP·gRPC 호스트를 `192.168.0.20`으로 바꾼다. 공유기의 DHCP 주소 예약을 권장하며, DB PC 방화벽에서 개발 PC IP의 TCP 3307·6333·6334 접근만 허용한다. 공유기 포트 포워딩은 사용하지 않는다. 기본 구성은 신뢰하는 로컬망용 평문 연결이며 신뢰하지 않는 네트워크에서는 TLS 또는 VPN을 추가한다.

Compose 파일을 다른 PC로 복사해도 named volume 데이터는 따라가지 않는다. 기존 데이터를 옮길 때는 MySQL 논리 백업·복원과 Qdrant 스냅샷·복원을 별도로 수행하거나 원문에서 색인을 재생성한다. 같은 Docker 호스트에서 독립된 환경을 추가로 만들려면 프로젝트 이름(`-p`)과 호스트 포트를 모두 구분한다.

## 초기 구동 검증 결과

- MySQL `8.4.11` 이미지 기동, 사용자 계정의 `SELECT 1`과 버전 조회, `healthy` 상태 확인.
- Qdrant `v1.19.1` 기동, 키 없는 요청 거부, 인증된 임시 컬렉션 생성·벡터 저장·검색·삭제 확인.
- 실제 컨테이너 CPU·메모리 상한이 MySQL 2 CPU·2 GiB, Qdrant 4 CPU·6 GiB로 적용됨을 확인.
- 초기 빈 데이터 상태에서 MySQL 약 570 MiB, Qdrant 약 120 MiB를 사용했다. 색인 후 용량·성능 추정치로 사용하지 않는다.
- 최초 지정한 MySQL `8.4.12` 태그는 레지스트리에 없어 배포된 `8.4.11`로 수정했다. 호스트 3306 바인딩이 거부되어 기본 호스트 포트를 3307로 변경했다.
- 백엔드 JDBC 연결, gRPC 클라이언트 연동, 법령 색인 품질, 재기동 후 데이터 복원, 다른 PC에서의 LAN 접속은 별도 검증 대상이다.

## 참고 문서

- [Docker Compose 서비스 설정](https://docs.docker.com/reference/compose-file/services/): CPU·메모리·포트·볼륨 설정.
- [MySQL 8.4 릴리스 노트](https://dev.mysql.com/doc/relnotes/mysql/8.4/en/): 서버 버전 변경 사항.
- [Qdrant 릴리스](https://github.com/qdrant/qdrant/releases): 고정한 서버 버전.
- [Qdrant 설정](https://qdrant.tech/documentation/operations/configuration/): 환경변수와 API 키 설정.
- [Qdrant 저장소](https://qdrant.tech/documentation/manage-data/storage/): 컬렉션의 메모리·디스크 저장 전략.
