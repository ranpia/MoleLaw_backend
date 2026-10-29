# 전체 RAG 시험 준비

2026-10-29. **유료 AI 호출 없이 준비와 배선 검사를 완료했다.** 합성 AI와 실제 KURE·Qdrant·앱/DB·인증·NDJSON을 연결해 정상 완료 및 추가 수집 차단을 확인했다. 유료 전체 RAG의 성능·품질을 측정한 결과는 아니다.

## 준비된 환경

| 구성 | 역할 |
| --- | --- |
| `rag-backend`, localhost:18083 | 기존 서버와 별도 이미지/컨테이너. 무저장 답변 API와 기존 인증/CSRF 사용 |
| `rag-model` | 모든 질문 분석·근거 읽기/판단·최종 생성 호출을 통과시키는 공통 게이트웨이. 기본 synthetic |
| `molelaw_service_loadtest_rag_app_v1` | 앱 계약에 맞춘 전용 Qdrant 컬렉션, 20조문·29청크·1,024차원 |
| 시험 MySQL 원문/색인 | 기존 격리 `molelaw_loadtest` DB에 이 fixture의 원문 4개와 현재 지문의 COMPLETE 발행 4개 |
| Prometheus/Grafana | 시험 앱/AI 게이트웨이 연결, 역할별 호출·예산·로컬 모델 큐·마지막 배선 검사 시간 |

기존 1,000개 성능 컬렉션·기존 백엔드 컨테이너를 교체하지 않는다. 새 앱은 전용 이미지 `molelaw-app:ragprep`을 사용한다. 인증 사용자만 기존 합성 계정을 재사용한다. 실제 사용자 DB나 운영 컬렉션에는 쓰지 않는다.

초기 연결용 자료는 4종 법령에서 각 5개 조문을 선택한 **작은 20조문 fixture**다. 1,000개 전체 앱 색인이나 검색 품질 정답셋은 아니다. 본문 스냅샷을 앱의 조문 JSON 형태로 재구성하고 실제 LawArticleChunker로 다시 읽을 수 있는지 검사한다. 원래 OpenLaw API 응답 전체를 확보했다고 표시하지 않는다.

임베딩 입력은 앱과 같은 `title + "\n" + text`다. 기존 bare-text 벡터에 메타데이터만 덧씌우지 않고 이 형식으로 실제 생성했다. 크기는 앱 기준 Unicode code point 512·overlap 64이며 토큰 512와 다르다. 이미 준비된 동일 입력 벡터는 재사용한다. 앱의 collection identity/fingerprint·generation·source hash·원문 본문·LID/시행일을 맞추며, Java 계약 검사가 실제 지문과 청커를 재확인한다.

LID는 고정 MST의 공식 원문 페이지 hidden `lsId`를 확인했다: 민법 `001706`(MST 246569), 주택임대차보호법 `001248`(249999), 근로기준법 `001872`(285279), 저작권법 `000798`(260839). [민법](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=246569), [주택임대차보호법](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=249999), [근로기준법](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=285279), [저작권법](https://www.law.go.kr/LSW/lsInfoP.do?lsiSeq=260839). 이는 고정 과거 버전이며 최신 법률 조언용 자료가 아니다.

## 추가 수집과 유료 호출 제한

`app.law-retrieval.fixed-index-only=true`를 시험 앱에만 설정했다. 모델이 COLLECT_LAW를 제안하면 외부 법령 검색·원문 수집·추가 색인 전에 EVIDENCE_INSUFFICIENT로 종료한다. AI_SEARCH backend와 이 모드를 함께 설정하면 실행을 거절한다. 기본값은 false여서 기존 앱 동작은 유지된다. 무저장 API라는 이유만으로 검색까지 자동 읽기 전용이라고 가정하지 않는다.

현재 앱 검증의 리랭커 후보 최솟값은 **15개**다. 직접 모델 시험의 5개를 앱 설정에 억지로 허용하지 않고 첫 연결은 15개로 실행했다. 이후 앱 경로 비교는 15/20개로 준비한다. 초기 fixture는 20조문이므로 대규모 검색 성능으로 해석하지 않는다.

게이트웨이는 Chat Completions 한 경로와 `gpt-5.4-mini`/`gpt-5.4`만 허용한다. 임베딩/도구/임의 대상 URL은 전달하지 않는다. 실제 모드는 고정 `https://api.openai.com/v1/chat/completions`에만 연결하며 리다이렉트를 따르지 않고 앱의 dummy 키를 전달하지 않는다. 실제 키는 별도 Git 제외 파일에서만 읽는다.

- 실제 모드는 전체 역할 합산 **최대 8회·USD 5**다. 이미 수행한 22호출과 합쳐 최초 30회 계획의 남은 8회를 반영한 초기값이다. 상담 8개가 아니라 공급자 HTTP 시도 8개다.
- 요청 시작 전에 실제 전송할 메시지의 UTF-8 기반 입력 상한+최대 출력 비용을 예약한다. 모델별 실제 usage를 받으면 캐시 할인 없이 보수적으로 정산한다. reasoning을 출력 토큰에 다시 더하지 않는다.
- usage 누락·응답 실패·연결 유실이면 미정산 예약을 남기고 추가 요청을 중단한다. 원자적 파일 저장과 프로세스 잠금으로 재시작/중복 실행이 예산을 초기화하지 않게 한다. pending 상태로 재시작하면 자동 재개하지 않는다. key/질문/답변 원문은 게이트웨이 기록에 넣지 않는다.
- schema-only per-request 옵션을 사용한 근거 읽기 호출에서 완료 토큰 한도가 빠지는 것을 관측했다. 이 시험 게이트웨이는 누락 시 **4,096 한도를 명시적으로 보충**하고 해당 사실·수신 model/effort/한도를 기록한다. 운영의 역할별 설정 전달이 모두 보존됐다는 의미는 아니다. 16,384를 넘는 요청은 거절한다.
- 스트리밍은 include_usage를 강제하고 마지막 usage를 관측한다. 취소가 실제 공급자 계산/청구 중단을 보장하지 않으므로 미정산 예약을 해제하지 않는다. 이 제어는 시험 게이트웨이 범위이며 운영 전역 예산 기능은 아니다.

표준 가격은 [mini 공식 문서](https://developers.openai.com/api/docs/models/gpt-5.4-mini)의 입력/출력 USD 0.75/4.50, [GPT-5.4 문서](https://developers.openai.com/api/docs/models/gpt-5.4)의 2.50/15.00(각 1M토큰당)을 기준으로 설정했다. [스트림 usage 계약](https://developers.openai.com/api/reference/resources/chat/subresources/completions/streaming-events)에 따라 usage 누락을 별도 처리한다. 이후 실제 모드 전 가격·계정별 RPM/TPM·잔여 호출 예산을 재확인한다. 실제 청구 상한을 정확히 보장하는 기능으로 표시하지 않는다.

## 준비와 재확인

이전 합성 계정 및 고정 법령/질문 자료와 모델 캐시가 준비된 환경에서 실행한다.

```powershell
.\scripts\load-testing\prepare-full-rag.ps1
```

스크립트는 입력/벡터 해시 검사 → 앱 호환 색인 준비/재검증 → 전용 DB 원문·발행 기록 등록/해시 검사 → Java 계약 검사·bootJar → 게이트웨이 단위 테스트 → 전용 앱 빌드/준비 → 합성 연결 검사 순서로 실행한다. 기존 데이터는 지문과 소유 자료가 일치할 때만 재사용한다. SQL은 중복 PK를 덮어쓰지 않고 최종 4원문/4발행·29청크·원문 해시를 확인한다. 현재 대상 지문 외의 기존 발행 기록은 검색 대상이 아니다.

준비 스크립트는 실제 키를 읽거나 외부 모델에 연결하지 않는다. default internal Docker network를 유지한다. Grafana의 **MoleLaw - 전체 RAG 시험 준비** 대시보드(UID `molelaw-rag-preparation`)에서 확인한다. 클라이언트 관측 단계 간격은 내부 추론 시간/분산 trace와 다르다. 마지막 정상 검사 값이며 부하 p95가 아니다. synthetic의 사용량 숫자는 합성 값이고 과금은 0이다.

현재 관측 대상은 기존 9개와 신규 시험 앱/게이트웨이 2개, 총 11개다. Prometheus에는 file discovery로 추가했으므로 시험 환경을 중지한 뒤 `build/loadtest-secrets/rag-targets.json`을 `[]`로 바꾸면 신규 대상만 제거된다.

자료: `build/rag-dataset/legal-perf-v1/app-rag-v2/manifest.json`, `seed.sql`, `build/rag-prep/probe/preflight.json`, `latest.json`, `build/rag-prep/model/synthetic-requests.jsonl`. 모두 Git 제외다. 유료 모드 ledger/기록은 별도 `live-*` 파일이며 자동 삭제/초기화하지 않는다.

검증은 전체 JUnit 547건·Java 자료 계약 1건·게이트웨이 8건 모두 통과했다. 별도 게이트웨이 프로세스가 같은 ledger 잠금을 획득하지 못하는 것도 확인했다. 실제 공급자 전달·청구/RPM/TPM 한도는 아직 실호출로 검증하지 않았다.

## 이후 실제 모델 1상담 실행 방법 — 이번에는 미실행

다음 명령은 실제 과금이 발생하는 별도 단계다. 이번 준비에서는 키 파일 생성도 하지 않았다.

```powershell
.\scripts\load-testing\prepare-rag-live-key.ps1
$composeArgs=@('--env-file','docker/loadtest/.env','-f','docker/loadtest/compose.yml','-f','docker/loadtest/rag.compose.yml','-f','docker/loadtest/rag.live.compose.example.yml','--profile','ragprep')
docker compose @composeArgs up -d --no-deps --force-recreate rag-model
docker compose @composeArgs run --rm --no-deps rag-probe --live
```

실제 모드에서만 model_download 외부 통신 network와 키 파일을 추가한다. 일반 준비 모드에는 추가하지 않는다.

실제 probe는 정상 질문 **1개만** 요청한다. 보조 호출이 반복되면 8회 한도에 먼저 도달해 실패할 수 있으며 이를 자동 재시도하지 않는다. 역할별 호출 수·usage·모델 옵션과 인용 실패 여부를 먼저 검토한 뒤 동시성을 늘린다. 이전 최종 생성 시험의 간헐적 인용 실패가 해결됐다고 가정하지 않는다.
