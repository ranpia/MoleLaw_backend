# 법령 벡터화와 Top 15 검색 도구

## 범위와 호출 순서

두 도구는 서버 내부 서비스이며 LLM의 `@Tool`로 자동 등록하지 않습니다. 최초 상담에서는 근거 판단 Client가 제안하고 서버 수집기가 명시적으로 호출합니다. 2026-10-22 Spring AI ETL 단계로 분리했고, 인증·CSRF를 유지한 local 전용 테스트 API와 저장 원문 재색인을 추가했습니다. [실행 방법과 제한](law-etl-local.md)을 따릅니다.

1. 서버가 사용자 인증·상담방 소유권을 검증하고 `LegalToolContext`를 생성합니다.
2. `LegalApiToolService`로 법령을 검색하고 상세 원문을 수집합니다.
3. `LawVectorizationToolService.vectorizeLaw(context, sourceReferenceId)`에 같은 실행에서 수집한 `LegalSource` 참조를 전달합니다.
4. `LawRagSearchToolService.searchTop15(context, question)`으로 적재된 공개 법령을 검색합니다.

`FIRST_QUESTION`, `PRECOLLECTION` 실행만 허용합니다. `FOLLOW_UP`은 거부하며 실행 목적·예산·참조 ID를 모델이 임의로 지정하게 연결하면 안 됩니다. HTTP 호출마다 용도별 실행 예산과 마감 시간을 적용합니다. 검색/수집과 임베딩·색인은 각각 독립 누적하며 [ETL 예산](law-etl-local.md)을 따릅니다.

## 임베딩과 저장

원문 조문을 분리하고 겹침을 둔 청크를 만들며, 기존 Python 서버의 `/api/tags`, `/api/embed` 계약을 사용합니다. 실험 브랜치 서버는 FastAPI가 아닌 Python 표준 HTTP 서버입니다. 서비스용 threaded KURE는 docker/kure에서 별도로 실행합니다. 실험 서버와 포트를 분리하며 [Docker 가이드](../../docker/README.md)를 따릅니다.

모델 태그·고정 revision·벡터 차원을 확인하고 문서와 질문에 같은 모델을 사용합니다. 임베딩 배열 크기와 유한한 숫자를 검증하며 잘못된 응답은 적재하지 않습니다. 반환 벡터를 Qdrant에 직접 기록하므로 `VectorStore.add()`를 통한 재임베딩은 하지 않습니다.

서비스 전용 컬렉션을 사용합니다. 실험용 컬렉션을 재사용하거나 기존 컬렉션의 차원·거리·모델 계약을 자동 변경하지 않습니다. MySQL legal_source_index에 원문 UUID·해시·모델 revision·설정 fingerprint·generation·WRITING/COMPLETE/FAILED를 기록합니다. 모든 임베딩 검증·Qdrant 배치 쓰기·포인트/출처/벡터 재조회 검증이 성공한 뒤 COMPLETE로 공개합니다. 검색은 현재 설정과 일치하는 완료 세대만 허용하고 반환 출처를 원문과 대조합니다. 실패·중단 세대의 일부 점은 검색되지 않으며 물리 정리는 별도 운영 작업입니다. 동일 내용·설정의 완료 세대도 저장 검증을 통과해야 재사용합니다. 포인트 누락이 확인되면 새 세대로 한 번 재처리하며, 검증 통신 장애는 누락으로 간주하지 않습니다.

기본 청크 크기는 Unicode 코드 포인트 300개, 겹침 64개입니다. 원문은 최대 2 MiB, 생성 청크는 최대 8,192개로 제한합니다. 큰 법령은 임베딩·저장 호출 수와 CPU 처리 시간이 증가하므로 서버가 생성하는 실행 예산을 충분히 설정해야 합니다. 제한을 임의로 높이기 전에 실제 모델 처리량을 확인하세요.

## 검색 결과

같은 MST에서 가장 최근 수집 원문에 해당하는 성공 세대만 공개하며 실패한 대체 색인은 이전 성공 세대를 숨기지 않습니다. 다른 MST의 과거·현재 시행 버전은 질의 기준일에 필요하므로 일괄 삭제하지 않습니다. 실제 확보한 시행일·조문 경로를 최초 상담 근거에 고정합니다.

청크를 조문 단위로 그룹화해 서로 다른 조문을 최대 15개 반환합니다. 기본은 각 조문의 가장 높은 청크 점수순입니다. [조문 리랭커](article-reranking.md)를 활성화하면 후보를 기본 50개로 넓혀 검증한 뒤 질문·대표 청크를 비교해 15개를 반환합니다. Cosine 점수는 보존하고 반환 순서만 바꾸며 임계값은 적용하지 않습니다. 데이터가 적으면 결과도 15개보다 적습니다. 컬렉션이 없으면 생성·임베딩 없이 빈 결과를 반환합니다. 점수는 정확도 확률이 아닙니다. 결과에는 출처와 조문을 확인할 수 있는 메타데이터를 포함합니다. 검색 도구 자체는 추가 법령 수집이나 답변 생성을 수행하지 않습니다.

## 운영 전 확인

동일 원문 재사용·변경 청크만 임베딩·MySQL ID/해시 관계와 중복 적재 방지는 [증분 색인](incremental-law-indexing.md)을 따른다. 재사용 벡터도 새 세대의 출처 메타데이터로 기록해 이전 시행 버전을 보존한다.

`app.law-vector.enabled`를 명시적으로 켜고 임베딩 서버와 Qdrant REST 접속 설정을 준비하세요. 모델 revision 또는 입력·청크 형식 변경 시 새 컬렉션을 사용해 다시 적재하세요. 외부 서비스 응답 본문이나 인증 키를 도구 오류에 노출하지 않습니다.

로컬 설정 예시는 다음과 같습니다. 비밀 키는 환경변수로 주입하세요.

```yaml
app:
  law-vector:
    enabled: true
    embedding-base-url: http://localhost:11437 # 서비스용; 코드 기본값 11436과 구분
    qdrant-base-url: http://localhost:6333
    collection-name: molelaw_service_law_kure_v1
    api-key: ${QDRANT_API_KEY:}
```

## 검증

### 실제 KURE 서버 검증 (2026-10-21)

기존 `molelaw-kure-lab-kure-1` 컨테이너를 시작해 `http://127.0.0.1:11436`에서 실제 KURE 고정 revision·CPU float32 런타임을 확인했습니다. `embeddingLiveTest`가 실제 임베딩 서버와 독립 Qdrant 1.19.1 컨테이너에서 두 Tool을 호출했습니다. 검증용 20개 조문 → 40개 청크 → 1024차원 벡터 저장 → 서로 다른 조문 15개 반환과 점수 정렬·출처 보존을 확인했습니다. 테스트 본문 실행은 컨테이너 시작 포함 약 7초였으며 모델 성능 벤치마크 수치는 아닙니다.

첫 실호출에서 Python 서버가 `Content-Length` 없는 요청을 HTTP 400으로 거절하는 문제가 발견돼 JSON을 UTF-8 바이트로 직렬화하고 길이를 명시하도록 수정했습니다. HTTP 대역 테스트도 길이 누락을 거절하도록 보완했고 실제 서버의 후속 임베딩 요청은 모두 200이었습니다.

```powershell
docker start molelaw-kure-lab-kure-1
.\gradlew.bat embeddingLiveTest
```

서버 주소를 바꾸려면 `LAW_EMBEDDING_LIVE_URL`을 지정합니다. 이 테스트는 일반 `test`에서 제외되며 실행 중인 실제 모델 서버와 Docker가 필요합니다. 원문 저장소만 테스트 대역으로 대체하고 임베딩·Qdrant는 실제 호출합니다. 입력은 실제 법령이 아닌 통제된 검증 자료이며 실제 법령 수집·MySQL 저장·검색 정확도 평가를 완료한 것은 아닙니다. 기존 서비스·실험 컬렉션은 변경하지 않습니다.

### 일반·격리 테스트

`.\gradlew.bat test`로 일반 테스트를 실행합니다. `.\gradlew.bat qdrantIntegrationTest`는 기존 데이터와 분리한 Qdrant 1.19.1 컨테이너에서 컬렉션 메타데이터, 저장 완료, 법령만 대상으로 한 상위 15개 조문 그룹과 정렬을 검증합니다. Docker가 필요하며 실제 임베딩 모델은 호출하지 않습니다. REST 계약은 [Qdrant 공식 API](https://api.qdrant.tech/api-reference/search/query-points-groups)를 참고했습니다.

`LawVectorClientTest`는 실제 loopback HTTP 요청으로 모델 고정, 잘못된 벡터, 컬렉션 계약, 적재 완료 응답, 검색 요청과 실패 처리를 검증합니다. `LawVectorPostmanSimulationTest`는 별도 로컬 Postman 임베딩 대역을 사용하는 선택 실행입니다. 대역은 계약 검증용 2차원 상수 벡터이며 실제 검색에 사용하면 안 됩니다.

```powershell
# 터미널 1: 로그인·클라우드 게시 없이 로컬 실행
postman simulation run postman/simulations/law-embedding.sim.yaml --port 14036 --no-history --output ndjson
# 터미널 2
$env:LAW_VECTOR_SIMULATION_URL='http://127.0.0.1:14036'
.\gradlew.bat test --tests '*LawVectorPostmanSimulationTest' --rerun-tasks
Remove-Item Env:LAW_VECTOR_SIMULATION_URL
```

검증 후 터미널 1의 서버를 Ctrl+C로 종료하세요. 해당 환경변수가 없으면 이 테스트는 건너뜁니다. 실제 클라이언트의 `/api/tags`, `/api/embed` 요청과 두 HTTP 200 응답을 Postman 요청 로그에서 확인했습니다.

전체 ETL 작업 큐·재시도 스케줄러·분산 부하 제어는 포함하지 않습니다. 최초 상담 수집기와 판단 Client, 상담방 근거 고정에는 연결했습니다. 로컬 HTTP 대역 테스트는 실제 모델 품질과 실제 Qdrant 검색 품질 검증을 대신하지 않습니다.

현재 코드·검증의 요약은 [개발 현황](../project/development-status.md)·[검증 현황](../validation/validation-status.md), 미완료 작업과 정책 선택은 [계획](../plans/README.md)·[사용자 판단](../decisions/README.md)에 둡니다.

## 수집 중복과 DB 갱신 정책 (2026-10-23)

- 이번 상담의 검색 근거에서 확인한 MST와 이미 상세 조회한 MST는 후속 법령 후보에서 제외한다. 다른 MST인 시행 버전과 시행령·시행규칙은 별개다.
- 이전 상담에서 완료한 동일 MST·법령ID·시행일·임베딩 설정의 색인이 있으면 저장 원문을 재사용한다. 재사용 시 기존 청크 수·Qdrant 본문 검증을 통과해야 하며 누락·불일치는 기존 재색인 경로로 복구한다.
- legal_source는 인용 스냅샷이 참조하는 불변 원문이므로 덮어쓰지 않는다. 새 시행 버전은 새 원문·색인 세대로 저장하고 완료 후 검색에 공개한다. 기존 상담의 근거는 변경하지 않는다.
- DB 스키마 변경은 없다. 동일 MST의 공급자 정정 탐지를 위한 주기적 원문 재조회·갱신 주기와 다중 요청의 동시 수집 잠금은 별도 후속 과제다. 이 중복 검사는 최신성 확인을 대체하지 않는다.
