# 판례 검색 범위·전체 페이지·최종 답변 분리

2026-10-24 최신 사용자 선택을 반영한다. 검색 범위를 매번 모두 실행하던 이전 설계는 아래 도구 선택 방식으로 대체했다.

## 처리 흐름

1. 후속 상담은 먼저 근거 준비 단계를 실행한다. 이 단계의 모델 텍스트는 사용자 답변으로 보내지 않고 progress/info만 전달한다. 외부 판례 검색은 이번 요청의 allowCaseSearch=true일 때만 허용한다.
2. LLM이 searchRoomCases의 scope를 선택한다. 생략하면 BODY다. BODY는 본문(search=2), TITLE은 사건명(search=1), LAW_REFERENCE는 본문에 방 법령의 JO 필터를 적용한다. 세 범위를 무조건 모두 호출하지 않는다.
3. 별도 CaseQueryPlanner가 대화의 쟁점을 최대 3개의 짧은 법률용어로 변환한다. 앞 검색이 비거나 본문 관련성 미달이면 다음 용어를 서버가 자동 시도한다. 같은 조건 반복은 생략한다.
4. 선택 범위에서 display=100으로 첫 페이지부터 hasNext=false까지 목록을 수집한다. 원문 식별자로 중복을 제거한다. 시간·API 호출·후보 수 제한으로 중단하면 searchComplete=false를 반환하고 로그에 미완료를 남긴다.
5. 수집된 목록 전체에서 제목의 검색어 일치 후보를 먼저 배치한 뒤 회차당 최대 4건, 요청당 최대 6건의 판결 원문을 확보한다. 이 정렬은 간단한 후보 우선순위이며 본문의 법적 관련성 평가를 대신하지 않는다. 본문 리랭크 LLM은 판시사항·판결요지·참조조문·판결본문 발췌를 법령과 쟁점에 비교한다.
6. 기본 관련성 60점 이상 중 상위 2개를 읽기 후보로 제공한다. 참조조문은 동일 법령/조문의 연결을 판단하는 자료로 주고 서버가 정확 일치를 강제하지 않는다. readRoomCase로 실제 읽은 판례만 최종 인용 근거가 된다.
7. 준비가 끝나면 도구 없는 최종 작성 단계에서 실제 답변만 스트리밍한다. 이미 읽은 판결 본문과 자연어 검색 요약을 제공한다. 내부 상태 코드가 조각으로 생성돼도 사용자 문장으로 치환한다. 동의 질문은 서버가 구성한 근거 포함 문구를 한 번만 보낸다.
8. UUID 인용·길이 검증 후 질문·답변·새 인용 판례를 원자적으로 저장한다. 중간 인용은 근거 번호로 표시하고 최종 인용 계약은 유지한다. 새 판례를 읽으면 info에는 출처·요지가 추가되며 마지막에는 실제 인용한 판례만 남긴다.

## 공식 가이드 대조

- [판례 목록 API](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=precListGuide): search=1/2는 사건명/본문, JO는 참조법령명이다. 특정 사건번호는 nb이며 search=3은 참조판례 항목의 검색이다. 현재 상담 도구의 세 범위와 사건번호 조회를 구분해야 한다. 사건번호/참조판례 전용 경로는 이번 변경에 추가하지 않았다.
- 같은 목록 가이드의 기본 정렬은 선고일 내림차순이다. 첫 페이지 결과는 관련도 순위가 아니므로 전체 페이지 목록 수집과 후속 평가를 분리한다. 여러 용어를 한 질의에 넣었을 때의 불리언 의미를 가이드가 정의하지 않으므로 모델이 만든 짧은 변형 질의를 개별 요청한다.
- [판례 본문 API](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=precInfoGuide): ID로 본문을 받고 판시사항·판결요지·참조조문을 활용한다. 일부 데이터 출처는 JSON 본문 제공에 제약이 있으므로 본문 수집 실패를 판례 부재로 처리하지 않는다.
- [시행일 법령 목록 API](https://open.law.go.kr/LSO/openApi/guideResult.do?htmlName=lsEfYdListGuide): 현재 법령 수집의 target=eflaw, 법령명/본문 범위, 현행 nw=3 사용을 대조했다. 법령과 판례의 같은 이름 파라미터도 대상별 의미를 구분한다.

## 제한과 환경 설정

| 환경변수 | 기본값 |
| --- | --- |
| APP_CASE_SEARCH_MAX_API_CALLS | 64회 |
| APP_CASE_SEARCH_MAX_CANDIDATES | 5000개 목록 후보 |
| APP_CASE_SEARCH_MAX_DETAILS | 요청당 본문 6건 |
| APP_CASE_SEARCH_DETAILS_PER_SEARCH | 회차당 본문 4건 |
| APP_CASE_SEARCH_MAX_SELECTED | 채택 2건 |
| APP_CASE_SEARCH_MINIMUM_SCORE | 60점 |
| APP_CASE_SEARCH_EXCERPT_CHARS | 본문당 4000자 발췌 |
| APP_CASE_SEARCH_MAX_RERANK_CALLS | 리랭크 2회 |
| APP_AI_CASE_QUERY_MODEL / REASONING_EFFORT / MAX_COMPLETION_TOKENS | gpt-5.4-mini / low / 2048 |
| APP_AI_CASE_RERANK_MODEL / REASONING_EFFORT / MAX_COMPLETION_TOKENS | gpt-5.4-mini / low / 4096 |

도구 최대 8회·판례 실행 컨텍스트 90초·전체 판례 읽기 12000토큰·새 인용 판례 최대 2건·방 최대 20건을 유지한다. 목록 전체 수집과 판결 본문 검토량은 별도 제한이다. 판례 리랭크는 LLM이며 조문 검색의 로컬 BGE와 별도다. 준비용 모델 호출과 최종 작성용 스트림 호출도 분리되므로 모델 호출이 추가된다. 수집 원문은 기존 legal_source 불변 스냅샷으로 저장하고 새 DB 테이블이나 판례 벡터 컬렉션은 만들지 않는다.

## 로그

상담·판례 요약 줄마다 같은 traceId, 유형=최초/후속, 방 번호를 표시한다. 선택 범위·법률용어 생성 개수·질의 시도 순번·페이지별/누적 후보·전체 수집 완료 여부·본문 확보·리랭크 점수·채택/제외 사유·최종 완료/오류를 남긴다. 질문 본문·자유 형식 판단·키는 기록하지 않으며 질의는 지문으로 구분한다.

```powershell
docker logs --since 30m molelaw-app-local-backend-1 2>&1 | Select-String '유형=후속'
```

최초 요약은 유형=최초로, 한 실행은 복사한 traceId로 필터링한다. 내부 상태는 진단용으로 유지하지만 최종 사용자 답변에는 자연어로 전달한다. 검색을 끝내지 못한 경우나 판례를 찾았지만 본문 예산에 걸린 경우를 전체 판례의 부재로 표현하지 않는다.
