# 근거 수집 LLM 판단 계약

`law.dto.retrieval.EvidenceDecision`은 최초 질문의 근거 판단 결과를 표현하는 내부 계약입니다. LegalEvidenceCollector가 최초 상담의 Qdrant 검색·전용 판단 Client·법령 수집·벡터화·재검색 반복에 연결합니다. 후속 상담과 SSE에는 연결하지 않습니다. 현재 실행 상한은 [최초 상담](initial-consultation.md), 모델은 [역할별 Client](role-chat-clients.md)를 따릅니다.

## 상태와 필드

모든 JSON 필드를 명시합니다. 사용하지 않는 목록은 `[]`이며 `null`로 보내지 않습니다. `reason`은 판단 사유로 모든 상태에서 필수입니다.

| action | 필수 조건 | 실행 제안 |
| --- | --- | --- |
| `ANSWER_READY` | `evidenceIds` 1개 이상, 나머지 목록은 비움 | 답변 생성 가능 |
| `ANSWER_PARTIAL` | 읽은 `evidenceIds`와 `missingIssues` 각각 1개 이상, 검색 목록은 비움 | Qdrant 경로에서 확인된 근거로 일반 안내만 생성. 미확인 쟁점을 응답에 명시 |
| `SEARCH_AGAIN` | `missingIssues`·`searchQueries` 필수, `lawSearches` 비움 | 기존 Qdrant 색인 재검색 |
| `COLLECT_LAW` | `missingIssues`·`lawSearches` 필수, `searchQueries` 비움 | 법령 목록·원문 추가 수집 제안 |
| `INSUFFICIENT` | `missingIssues` 필수, 검색 목록 둘 다 비움 | 현재 근거 부족·추가 확인 필요 |

마지막 세 상태의 `evidenceIds`에는 유효한 기존 근거 일부를 남길 수 있습니다. ID는 모델에게 실제 제공한 근거 ID만 허용하며, 서버 수집기가 버전별 인용 UUID를 부여하고 선택된 동일 근거를 상담방 스냅샷에 고정합니다. 이 계약의 필드 검증은 판단의 법적 타당성·충분성을 보증하지 않습니다.

```json
{
  "action": "COLLECT_LAW",
  "reason": "계약 해제 후 원상회복 의무를 판단할 조문이 없습니다.",
  "missingIssues": ["계약 해제의 효과와 원상회복 의무"],
  "evidenceIds": [],
  "searchQueries": [],
  "lawSearches": [
    {"query": "민법", "field": "TITLE", "exactName": true, "ministry": null}
  ]
}
```

`field`는 `TITLE` 또는 `BODY`이고 `exactName=true`는 제목 검색에서만 허용합니다. 이를 통해 민법을 찾을 때 난민법 같은 부분 일치 결과를 피할 의도를 전달합니다. 실제 Tool의 `search=1/2` 변환, 필터·페이지 순회·후보 ID·원문 조회는 서버 책임입니다. 모델이 원문 URL·MST·페이지 번호·자격 증명을 정하지 않습니다.

## 서버 검증

모델 응답은 `EvidenceDecision.parse(json, suppliedEvidenceIds, mapper)`로 처리합니다. 상태별 필드 모순, 미등록 ID, 중복·빈 값·과도한 길이, 알 수 없는 필드·상태, 숫자 enum, 중복 JSON 키, 추가 JSON 값은 거절합니다. 오류에는 원문 모델 출력을 포함하지 않습니다. 이 경계를 통과해도 서버의 권한·예산·중복·진행 여부 검사를 통과해야 실행할 수 있습니다.

제한: 근거 ID 15개, 누락 쟁점 8개, 재검색 질의/법령 검색 조건 각각 최대 3개. 질의 최대 200자, 사유 1,000자, 입력 JSON 32,768 Java 문자입니다. 목록은 방어적으로 복사합니다. 더 많은 실행은 후속 판단에서 서버가 조정합니다.

## 예산·부하·상태 알림의 별도 경계

- **LLM:** 서버 알고리즘으로 각 응답의 실제 입력·출력·추론 토큰 사용량을 누적·정산할 계획입니다. 호출 전 임시 예약과 최종 답변용 예약이 필요하며, usage 누락을 0으로 취급하지 않습니다. 모델은 예산을 설정하거나 소진 여부를 판단하지 않습니다. 아직 구현 전입니다.
- **로컬 임베딩:** 토큰 예산과 별도로 동시 처리 수·유한 대기열·배치 크기·대기 시간·재시도/중복을 제어합니다. 현재 처리량을 측정해 단일 추론과 유한 대기열 값을 조정합니다. 현재 프로세스당 동시 배치 1개·대기 4개·대기 5초 제한을 구현했습니다. 설정은 app.law-vector.embedding-concurrency/embedding-queue-capacity/embedding-wait-millis입니다. 다중 프로세스의 분산 제어는 후속입니다.
- **SSE:** LLM/수집/임베딩 실행 상태를 전달하는 후속 추가사항입니다. 현재 엔드포인트·이벤트·스트리밍 상태 저장을 추가하지 않습니다. 판단 계약에 연결 상태나 진행 이벤트를 섞지 않습니다.

검증: `.\gradlew.bat test --tests '*EvidenceDecisionTest'`. 최초 상담 호출 연결은 구현했고 실제 법률 판단의 품질 검증은 다음 단계입니다.

## 실제 모델 검증 기록 (2026-10-21)

`evidenceDecisionLiveTest`가 실제 `evidenceCollectionChatClient` 설정과 `EvidenceDecision.parse`를 사용해 OpenAI `gpt-4`를 호출했습니다. 기본·환경변수/.env 모델 설정을 사용하고 출력은 호출당 최대 1,024토큰, 자동 재시도는 비활성화했습니다. DB·법령 API·임베딩·추가 수집·SSE는 실행하지 않았습니다. 사용자 기록이 아닌 통제된 합성 질문 4개입니다.

| 시나리오 | 계약 통과 결과 | 입력 토큰 | 출력 토큰 |
| --- | --- | ---: | ---: |
| 충분한 검증용 규칙 | ANSWER_READY | 1,274 | 113 |
| 관련 조문이 기존 색인에 있음 | SEARCH_AGAIN | 1,286 | 154 |
| 민법 조문 미수집 | COLLECT_LAW | 1,276 | 175 |
| 행위·사실관계 미상 | INSUFFICIENT | 1,233 | 115 |

성공한 4건의 확인 가능한 usage 합계는 입력 5,069·출력 557, 총 5,626토큰입니다. **별도로 첫 SEARCH_AGAIN 호출 1건이 계약 검증에서 거절됐습니다.** 원문을 기록하지 않아 상세 위반 형태는 확인하지 못했고, 이후 같은 입력 재호출은 통과했습니다. 그 최초 거절 요청의 usage는 당시 테스트가 출력하지 않았으므로 위 합계는 전체 5회 호출의 청구 합계가 아닙니다. 테스트를 보완해 이후에는 파싱·판단 실패 여부와 무관하게 usage를 먼저 기록합니다. 실패 응답의 사용량도 정산해야 합니다.

이 기록은 네 상태의 실호출 경로를 확인한 결과이며 반복 안정성·실제 법률 판단 정확도 평가가 아닙니다. 재호출은 테스트 중 수동으로 실행했으며 운영 자동 재시도를 추가하지 않았습니다. BeanOutputConverter 형식 안내와 전용 판단 프롬프트를 적용해도 엄격한 서버 검증을 생략할 수 없습니다.

```powershell
.\gradlew.bat evidenceDecisionLiveTest
```

실제 API 사용량이 발생합니다. 이 선택 테스트의 독립 모델 설정은 OPENAI_CHAT_MODEL을 읽으므로 역할별 APP_AI_EVIDENCE_MODEL과 다릅니다. 현재 테스트는 추론 옵션을 사용하므로 실행 전 OPENAI_CHAT_MODEL을 지원 추론 모델(예: gpt-5.4)로 명시해야 합니다. 기존 .env의 gpt-4를 그대로 사용하면 현재 역할 설정의 검증이 아닙니다. 과거 표의 1024 출력 한도는 당시 기록이며 현재 테스트 완료 토큰 한도는 12288입니다. 일반 `test`에서는 제외합니다. 특정 상태만 확인하려면 `EVIDENCE_DECISION_LIVE_ACTIONS`에 쉼표로 구분한 상태 이름을 지정합니다. 로그에는 상태·usage·시간과 제한된 필드 형식만 남기고 키·질문·모델 응답 원문은 출력하지 않습니다.

토큰 필드는 [OpenAI 공식 Chat Completions 계약](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)을 따릅니다. 실제 사용량 기반 예산 통제는 아직 별도 구현 대상이며 이 테스트는 사용량 관측만 수행합니다.

## 질문 분류와 검색 피드백

판단 입력은 retrievalPlan과 서버가 기록한 searchAttempts를 포함한다. 최근 최대 8개 시도를 토큰 예산 안에서 전달하며 최신 시도를 유지한다. 빈 결과·정확 제목 불일치·새 근거 없음·소진 상태를 보고 조건을 바꿀 수 있다. API 장애는 빈 결과로 바꾸지 않는다. 일반 안내에서는 누락 사실만으로 무조건 종료하지 않되 개별 허용 여부를 단정하지 않는다. [상세 계약](question-routing.md)을 참고한다.

판단 정확도·usage 정산·운영 보강은 [P01~P06](plans/README.md), 비용·품질 정책은 [사용자 판단](decisions/README.md)에서 관리합니다.

## 읽은 근거로 충분성 판단

최초 충분성 판단은 후보 전체 목록→본문 읽기 선택→MySQL 원문 확인→실제 읽은 본문 입력으로 변경했다. 기존 2500토큰은 판단 기본 JSON의 상한이며 본문을 그 안에서 한 조문만 남기는 방식은 사용하지 않는다. 후보 목록 4000·본문 누적 12000·결합 판단 입력 20000토큰의 추정 상한과 최대 2회 읽기 선택을 적용한다. [현재 계약과 제한](evidence-reading.md)을 따른다.
