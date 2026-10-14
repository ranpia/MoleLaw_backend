# 법령·판례 API Tool

2026-10-13: 서버가 명시적으로 호출하는 내부 어댑터 구현. 자동 tool-calling, MCP 서버, HTTP 공개 API, 상담 연결은 포함하지 않는다.

## 사용 계약

`LegalApiToolService`의 네 메서드를 사용한다. 모델 출력에는 `SearchRequest` 또는 후보 ID만 허용한다. 인증·상담방 소유권 확인 후 서버가 실행마다 별도 `LegalToolContext`를 생성하며 사용자/모델이 context·purpose·예산을 지정하게 하지 않는다.

```java
var clock = Clock.systemUTC();
var context = new LegalToolContext(LegalToolContext.Purpose.PRECOLLECTION,
        UUID.randomUUID().toString(), clock.instant().plusSeconds(60), 10, clock);
var page = tools.searchLaws(context,
        new LegalToolContracts.SearchRequest("민법", 1, "1270000", 1, 20,
                new LawSearchFilters("001706", "A0002", 3, true)));
if (!page.candidates().isEmpty()) {
    var detail = tools.getLawDetail(context, page.candidates().get(0).id());
    var raw = sourceService.restore(detail.referenceId()).getRawJson();
}
```

위 60초·10회는 사용 예시이며 운영 예산으로 확정하지 않았다. context는 한 실행 안에서만 사용하고 공유하지 않는다. `FOLLOW_UP`은 네 Tool 모두 외부 조회 전에 거부한다. 현재 ChatClient에는 Tool을 등록하지 않는다.

| 메서드 | 반환 |
| --- | --- |
| `searchLaws` | MST·법령ID·법령명·시행일 후보, 페이지·총건수·다음 페이지 여부 |
| `searchCases` | 판례 ID·사건명·사건번호·선고일·법원 후보와 페이지 정보 |
| `getLawDetail` | 원문 참조·버전·조문 수·최대 100개 조문번호·본문 확보 여부 |
| `getCaseDetail` | 원문 참조·판례 ID·제목·본문 확보 여부·출처 |

검색 범위는 1(제목), 2(본문), 페이지는 1~10,000, 크기는 1~100, 검색어는 최대 200자다. 법령은 숫자 부처 코드와 선택 정렬(기본 `lasc`)을 사용하고 판례는 법령 전용 필터를 거부한다. 기존 법령 fallback 순서는 유지하며 새 Tool은 요청한 검색 조건 하나만 실행한다.

### 검색 옵션 선택

| 옵션 | 사용 방법 |
| --- | --- |
| `exactName` | 법령명이 확정된 경우 true. API에 따옴표로 감싼 검색어를 전달하고 서버에서도 제목 완전 일치를 검사해 `민법`과 `난민법`을 구분한다. 제목 검색에서만 허용한다. |
| `lawId` / `kind` / `status` | 알려진 법령ID·종류·현행/연혁/예정으로 범위를 좁힌다. null은 해당 조건 생략이다. |
| `sort` | `lasc/ldes` 이름, `dasc/ddes` 공포일, `nasc/ndes` 공포번호, `efasc/efdes` 시행일의 오름/내림차순. null은 `lasc`다. |
| `effectiveDateRange` | `efYd`로 전달하는 시행일 범위. 예: `20200101~20261231`. |
| `promulgationDateRange` | `ancYd`로 전달하는 공포일 범위. 동일한 날짜 형식이며 유효한 날짜·시작일≤종료일만 허용한다. |

확장 생성자는 `new LawSearchFilters(lawId, kind, status, exactName, sort, effectiveDateRange, promulgationDateRange)`다. 기존 4인자 생성자도 유지한다. 정렬·날짜를 포함한 모든 조건은 중복/페이지 탐색 상태를 구분하는 데 사용한다. 시행일 범위 검색이 사건 당시 적용 법령을 자동 결정하지는 않는다.

법령명이 주어지면 정확명·제목 검색부터 사용하고, ID를 확인하면 ID로 좁힌다. 쟁점 탐색은 `exactName=false`와 제목/본문 검색을 명시적으로 선택한다. 결과 부족 시 부처·종류·날짜 조건을 바꿀 수 있으나 정확명 모드를 조용히 부분 검색으로 바꾸거나 첫 후보를 자동 채택하지 않는다.

`LawSearchFilters`로 법령ID(`LID`)·법령종류(`knd`)·상태(`nw`: 1 연혁, 2 시행예정, 3 현행)를 선택한다. 법령ID는 법령의 정체성, MST는 특정 개정본 식별자다. 부처·종류만으로 같은 부처의 유사 법령을 구분할 수 없으므로, 법령명이 정해졌으면 `exactName=true`로 응답의 법령명도 일치시킨다. 법령ID를 모르면 이름·부처·종류로 후보를 확인한 뒤 응답의 법령ID로 재조회한다. 민법의 실응답 값은 법무부 `1270000`, 법률 `A0002`, 법령ID `001706`이다. 이를 모든 법령에 적용하는 기본값으로 하드코딩하지 않는다.

정확한 이름으로 걸러낸 후보 수와 API의 `totalCount`는 다를 수 있다. 현재 페이지에 정확한 후보가 없어도 `hasNext=true`이면 예산 안에서 다음 페이지를 탐색할 수 있다. 판례 Tool은 법령 전용 필터를 거부한다. 기존 5인자 요청은 필터 없이 기존 검색을 유지한다.

## 실행·원문 보존

- 상세 ID는 같은 실행의 검색 결과와 대조한다. 상세 재호출은 저장된 참조를 반환한다.
- 같은 페이지 재조회는 `DUPLICATE`, 반복 후보는 `EXHAUSTED`, 정상 빈 결과는 `EMPTY`, 호출/기한 소진은 `BUDGET_EXHAUSTED`로 구분한다. 빈/마지막 페이지 이후는 조회하지 않지만 아직 방문하지 않은 앞 페이지는 허용한다.
- API 실패는 `LegalToolException`의 사유와 재시도 가능 여부로 구분한다. 자동 재시도는 없으며 실패한 호출도 예산에 포함한다. 늦게 도착한 응답은 저장 전에 거부한다. HTTP 제한 시간은 개별 10초이므로 남은 전체 기한보다 늦게 반환될 수 있다.
- `legal_source`에 UUID 참조·원문 JSON·SHA-256·수집 시각·MST/판례 ID·법령ID·시행일·키 없는 출처를 별도 스냅샷으로 보존한다. 외부 호출 후 짧은 저장 트랜잭션을 사용하며 기존 `Law`·`LawChunk`는 변경하지 않는다.
- `contentAvailable`은 본문 확보 여부다. 법적 관련성·완전성·적용 가능성 검증 완료를 의미하지 않는다.

## 스키마·검증·남은 작업

빈 MySQL DB의 `legal_source`는 Flyway V1이 다른 업무 테이블과 함께 자동 생성한다. [이전 수동 SQL](sql/001-legal-source.sql)은 참고용이며 별도로 실행하지 않는다. 기본/mysql·local은 JPA validate를 사용한다. H2/모킹 검증은 `.\gradlew.bat test`로 실행한다.

Flyway/JPA 원문 저장의 실제 MySQL 검증은 `mysqlIntegrationTest`로 수행한다. 실제 API→MySQL 전체 경로, 시행일별 상세 조회 정책, 영속적인 페이지/실패 재개 기록, 전체 동시성 제한, ETL·Qdrant·상담 오케스트레이터 연결은 남아 있다. 법령 상세는 기존 `target=law&MST=...` 계약을 재사용하며 목록 시행일/법령ID와 다르면 거부한다.

## 실제 API 연결 테스트

```powershell
.\gradlew.bat openLawLiveTest
```

`OC_KEY` 환경변수 또는 루트 `.env`의 키를 사용한다. 일반 `test`에서는 이 네트워크 테스트를 제외한다. 민법의 부처·종류·현행·정확명 검색, 응답 법령ID 재조회, 법령 상세와 판례 목록/상세를 총 5회 호출한다. 실제 Tool 계약과 원문 참조 복원을 검증한다. 원문은 테스트 메모리에만 보존하며 DB·OpenAI를 호출하지 않는다. 키·인증 URL·본문은 출력하지 않는다.

2026-10-13 실호출 통과: 필터 적용 전 `민법` 검색은 78건에 난민법을 포함했다. 부처·종류·현행 조건 적용 후 API 후보 2건에서 정확명으로 민법 1건만 반환했다. 응답 법령ID 재조회와 상세 조회를 통해 민법(MST 284415, 법령ID 001706, 시행일 20260317) 조문 1,337개를 확보했다. `손해배상` 판례 검색 9,209건 중 5건 반환, 첫 후보 ID 624553의 상세 본문 1,139자 확보. `LawSearch`·`법령`·`PrecSearch`·`PrecService` 응답 매핑을 확인했다. 검색 결과와 총건수는 시점에 따라 달라질 수 있다. 사건 발생일에 맞춘 연혁 선택과 법률 검색 품질 검증은 별도 작업이다.

공식 계약: [법령 목록](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=lsEfYdListGuide), [판례 목록](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=precListGuide), [판례 상세](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=precInfoGuide).
