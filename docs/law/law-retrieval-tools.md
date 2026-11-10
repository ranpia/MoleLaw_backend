# 최초 법령 수집의 LLM 도구

기준: 2026-11-10. 기본 QDRANT 최초 상담은 행동 JSON을 서버 switch로 실행하던 방식에서 실제 Spring AI `@Tool` 호출 방식으로 전환했다. HTTP JSON·SSE·NDJSON 경로는 유지한다.

## 도구와 실행 흐름

| 도구 | 모델이 선택하는 입력 | 서버가 수행하는 일 |
| --- | --- | --- |
| searchLawArticles | 필요한 검색 질의 목록 | Qdrant 검색·리랭크·복수 쟁점 후보 균형·버전 검증·발견용 목록 반환 |
| searchLaws | query, TITLE/BODY, exactName, 선택 ministry | 공식 법령 목록의 전체 페이지 수집·기존 정책의 후보 평가·수집 가능한 ID 반환 |
| collectLaw | 이번 실행에서 승인한 candidate ID | 기존 공개 원문 재사용 또는 원문 수집·증분 임베딩·중복 방지·색인 |
| readLawArticles | 현재 후보의 evidence UUID 목록 | 불변 원문에서 실제 조문 복원·출처/본문 검증·누적 읽기 예산 적용 |

QuestionRetrievalPlanner는 요청의 사실·질문·검색 표현을 분석한다. LawRetrievalToolFactory는 권한·시간·예산을 가진 요청별 LawRetrievalToolSession을 생성한다. LawToolCallingPlanner는 수집 역할 모델이 반환한 실제 tool call을 검증·실행하고 결과를 다음 모델 호출의 ToolResponseMessage로 전달한다.

검색에 실패하면 모델이 결과를 보고 질의·범위를 바꾼다. collectLaw가 색인해도 서버가 다음 검색어를 정하지 않으며 모델이 다시 searchLawArticles/readLawArticles를 호출한다. 임베딩·Qdrant upsert 자체는 별도 LLM 도구로 노출하지 않는다. 후보 목록·수집 성공만으로 인용할 수 없다.

본문 읽기가 성공하면 observer.evidence를 즉시 발행해 스트림 info/evidence에 반영한다. 최종 선택 때 비채택 근거는 제외한다. 준비 모델의 텍스트·도구 JSON·최종 근거 선택 JSON은 사용자 answer_delta에 보내지 않는다. 실제 답변은 기존 별도 답변 모델과 인용 검증 경로가 생성한다.

최종 결정은 ANSWER_READY/ANSWER_PARTIAL/INSUFFICIENT만 허용한다. EvidenceDecision의 기존 JSON 필드를 재사용하지만 SEARCH_AGAIN/COLLECT_LAW 제안으로 실행을 대신할 수 없다. 현재 후보이면서 실제로 읽은 ID만 최대 8개 선택할 수 있고 부분 답변은 미해결 쟁점을 명시해 일반 안내로 제한한다. 검색 없이 바로 INSUFFICIENT를 반환하면 실제 검색을 지시하는 한 번의 보정 기회만 제공한다.

## 키워드와 서버 제한 구분

별도 키워드 추출, 질문 분석의 검색 질의·검색 표현/후보 명칭, 판례 질의에 고정 개수 제한을 두지 않는다. 법령명·법률 용어뿐 아니라 사건명·식별어·사실관계·자연어 표현도 검색 목적에 맞게 선택한다. 질문별 첫 표현은 후보 균형을 위한 추천 목록이며, 전체 표현과 분석도 도구 모델에 함께 전달한다. 키워드 개수 때문에 3개/6개만 남기는 동작은 없다.

외부 API의 단일 질의 길이(OpenLaw 200자, 조문 검색 2000 코드포인트), 비어 있거나 중복된 입력 검증, 근거 ID 권한·원문 정합성 및 아래 실행/출력 예산은 유지한다. 키워드 추출 자유가 무제한 외부 실행이나 무제한 프롬프트를 의미하지 않는다.

| 서버 제한 | 현재 값 |
| --- | --- |
| 수집 역할 모델 왕복 | APP_LAW_RETRIEVAL_MAX_TOOL_STEPS, 기본 12·허용 1~32 |
| 전체 도구 호출 | 32, 모델 응답 한 번에 최대 4·서버 순차 실행 |
| 일반 처리 deadline | 기존 기본 3분. 임베딩 시간 계측/별도 제한은 기존 컨텍스트를 재사용 |
| 검색/수집 외부 호출 | 기존 실행 컨텍스트 128, 임베딩/색인 별도 예산 유지 |
| 한 요청의 수집 대상 | 최대 6개 법령 |
| 본문 읽기 | 호출당 최대 8개·누적 APP_AI_EVIDENCE_BODY_MAX_INPUT_TOKENS 기본 12000 |
| 모델 입력 | APP_AI_EVIDENCE_DECISION_MAX_INPUT_TOKENS 기본 32000. 시스템·도구 스키마·전체 transcript와 ToolResponse 본문까지 포함 |

모델이 마지막 왕복에 수집 도구를 요청해도 이후 검토 기회가 없으므로 실행하지 않는다. 도구 이름·호출 ID·인수 크기는 실행 전에 묶음 전체를 검증하고, 모르는 도구가 하나라도 있으면 해당 묶음의 작업을 시작하지 않는다. 도구 인수는 실행 컨텍스트·자격증명·endpoint를 지정할 수 없다. fixedIndexOnly에서는 searchLaws/collectLaw 콜백 자체를 등록하지 않는다.

lawToolChatClient는 근거 모델·완료 토큰 설정을 공유하고 내부 자동 도구 실행을 비활성화한다. GPT-5.4의 Chat Completions는 함수 도구와 reasoning_effort=medium을 함께 보내면 HTTP 400으로 거절하므로, 도구 전용 클라이언트는 none을 명시한다. 구조화 판단용 evidenceCollectionChatClient의 추론 설정은 독립적으로 유지한다. 도구 호출에 추론을 활성화하려면 Responses API 연결을 별도로 구현해야 한다. 요청에 새 옵션 객체를 통째로 덮지 않고 도구 목록만 연결한다. 서버 루프가 실행 한도를 통제하며 공급자 실패를 자동 재시도하지 않는다. 모델 왕복 증가로 인한 실제 시간·비용·법률 품질은 별도 평가 대상이다.

## 범위와 검증

기본 QDRANT 경로에 적용한다. 명시적으로 선택하는 AI_SEARCH 진단 경로는 기존 조문 API/구조화 판단을 유지하며 자동 fallback하지 않는다. 후속 판례 도구 흐름도 변경하지 않는다.

실제 Spring ToolCallbacks와 모의 ChatModel로 검색→읽기→확정, 빈 색인→후보 검색→수집→재검색→읽기, 읽지 않은 ID 차단, 알 수 없는 도구 묶음 차단, 고정 색인 모드·예산·취소·역할 모델 설정 보존을 검증한다. 기존 행동 JSON 중심 collector 테스트는 실제 도구 세션·호출 루프·진입 경계 테스트로 대체했다. 법령 검색/원문/색인 클라이언트 자체의 회귀는 유지한다. 실제 유료 LLM이나 법률 품질 검증과 대역 검증은 구분한다.
