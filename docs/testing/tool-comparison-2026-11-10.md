# 법령 도구 교체 후 전체 RAG 유료 비교

## 오류 원인 수정과 재실행

2026-11-10 재실행에서 **기존 HTTP 400은 해결했지만, 반복 조문 검색으로 시간 예산을 소진해 전체 상담은 실패했다.** 성공/실패 측정을 분리하며 아래 최초 실패 기록도 보존한다.

| 항목 | 이전 기준선 | HTTP 400 수정 후 재실행 |
| --- | --- | --- |
| 최종 상태 | completed | 앱 EVIDENCE_INSUFFICIENT / 발생기 180초 한도 초과 |
| 첫 답변 | 39.002초 | 없음 |
| 완료/중단 | 완료 42.365초 | 발생기 180.013초 중단, 앱 186.787초 오류 종료 |
| 공급자 시도 | 5회 성공 | 5회 모두 성공 |
| 입력/출력 토큰 | 15,866 / 3,043 | 17,471 / 1,048 |
| 캐시 할인 제외 추정 비용 | USD 0.04329075 | USD 0.05039025 |
| 이번 실행의 신규 미정산 예약 | 0 | 0 |

원인을 분리한 단일 공급자 진단(8번 요청)이 실제 HTTP 400, invalid_request_error, param=reasoning_effort를 반환했다. 메시지는 GPT-5.4 Chat Completions에서 함수 도구와 활성 추론의 조합을 지원하지 않으며 Responses API 또는 reasoning_effort=none을 사용하라는 내용이었다. 진단 원문은 키를 제외한 `build/rag-prep/model/provider-diagnostic.json`에 보존했다. [공식 함수 호출 문서](https://developers.openai.com/api/docs/guides/function-calling)와 [GPT-5.4 모델 문서](https://developers.openai.com/api/docs/models/gpt-5.4)를 함께 대조했으며, 이번 조합의 거절 근거는 실제 공급자 응답이다.

수정은 `lawToolChatClient`를 분리해 gpt-5.4 / none / 완료 한도 12,288 / 서버 수동 도구 실행으로 연결한 것이다. 구조화 판단용 evidenceCollectionChatClient의 별도 추론 설정은 유지한다. 모델 교체나 gateway의 요청 옵션 변조로 우회하지 않았다. 설정 회귀는 실제 도구 콜백이 있는 Prompt에서 none·모델·완료 한도·자동 실행 비활성화 및 구조화 판단의 high 옵션 독립성을 확인한다.

실제 재실행은 질문 분석 1회와 도구 모델 4회가 모두 HTTP 성공이었다. 모델은 searchLawArticles → searchLawArticles → readLawArticles → searchLawArticles를 선택했다. 본문 3개를 읽었지만 최종 결정을 내리기 전에 검색을 추가했다. 첫 검색 묶음은 약 82초, 발생기 관측 INITIAL_SEARCH 누적은 163.072초였다. 앱은 reason=BUDGET_EXHAUSTED, 실행 컨텍스트 calls=105로 종료했다. 105는 내부 실행 컨텍스트 카운터이며 유료 공급자 호출 수가 아니다. 이 실행은 반복 검색·리랭크 비용/시간이 다음 개선 대상임을 보여주며, 법률 품질이나 처리량 개선을 입증하지 않는다.

이번 재실행 reasoning 토큰은 질문 분석의 24개, cached input은 0이었다. 신규 미정산은 없지만 앞선 실패 7번의 USD 0.213325와 진단 8번의 USD 0.0263775는 미확인 비용 예약으로 그대로 유지했다. 명시적 재실행 시 별도 unresolved 항목에 옮기고 동일 USD 5 한도에서 계속 차감하며, 이를 0원 정산하거나 ledger를 초기화하지 않았다. 누적 확인 비용은 USD 0.09764475, 미확인 예약 합계는 USD 0.2397025다.

전체 Java 테스트 586건(실패·오류·건너뜀 0), 게이트웨이 11건, bootJar 및 합성 정상/수집 차단 경로가 통과했다. 재실행 뒤 synthetic 모드 복귀를 확인했다. 유료 성공 비교를 얻기 위해 시간 제한이나 검색 정책을 임의로 완화하지 않았다.

재실행 자료: `build/rag-prep/runs/tool-comparison-20261110T003121Z/`의 identity.json, probe.json, summary.json, 전후 ledger, requests-all.jsonl, backend-final.log. 기존 probe는 제한 시점 이후의 terminal 이벤트를 버릴 수 있어 이번 발생기 기록의 terminal은 null이고, 앱 최종 오류는 backend-final.log로 확인했다. 이후 probe는 늦은 terminal도 먼저 기록한 뒤 한도를 판정하도록 보완했다.

## 최초 유료 실패 기록

2026-11-10. `test/merged-feature-validation`에서 `0bf0dbd`의 실제 법령 Tool 호출 변경과 기존 로컬 리팩토링을 함께 검증했다. **합성 전체 경로는 통과했으나 유료 실행은 첫 도구 모델 요청의 공급자 HTTP 오류로 실패했다.** 지연·비용 개선으로 해석할 수 없다.

| 항목 | 이전 2026-10-29 | 도구 교체 후 2026-11-10 |
| --- | --- | --- |
| 질문 | 빌려준 돈을 돌려받지 못하면 어떻게 하나요? | 동일 |
| 색인 | 고정 법령 4종·20조문·29청크 | 동일 자료 해시 검증 |
| 최종 상태 | completed | GPT_API_FAILURE |
| 첫 답변 | 39.002초 | 답변 없음 |
| 완료/실패 종료 | 완료 42.365초 | 실패 6.149초 |
| 공급자 시도 | 5회 | 2회: 질문 분석 성공·첫 도구 요청 실패 |
| 확인된 usage 비용 추정 | USD 0.04329075 | USD 0.00396375, 성공한 질문 분석만 |
| 미정산 예약액 | 0 | USD 0.213325, 실제 비용으로 간주하지 않음 |
| 실제 도구 실행 | 기존 서버 실행 루프 | 유료 실행에서는 도구 실행 전에 실패 |

이번 질문 분석은 `gpt-5.4-mini / low`, 입력 1,259·출력 671토큰(reasoning 21 포함)이었다. 실패한 도구 모델 요청은 `gpt-5.4 / medium / max_completion_tokens=12288`이며 usage를 받지 못했다. 비용 추정은 기존과 같은 캐시 할인 제외 단가를 사용했다. [공식 가격표](https://developers.openai.com/api/docs/pricing)를 참고하며 실제 청구 금액과 구분한다.

게이트웨이가 당시 HTTPError의 응답 상태·오류 코드를 보존하지 않아 인증·요청 계약·공급자 제한 중 어느 원인인지 확정하지 못했다. 실패를 재시도하지 않았고 ledger는 attempts 7·누적 확인 비용 USD 0.0472545·pending 7번 USD 0.213325·stopped=true로 유지했다. 유료 모드는 종료하고 synthetic 모드로 복귀했다. 미확인 usage를 0원 처리하거나 ledger를 초기화하지 않았다.

재발 진단을 위해 게이트웨이에 공급자 HTTP 상태와 안전한 type/code/param만 기록하도록 보완했다. 원시 오류 본문·키·질문은 기록하지 않는다. HTTP 400을 합성한 회귀는 진단 필드 보존·원시 메시지 제외·예약 유지·중단을 검증했다. 이 회귀의 400/unsupported_parameter는 **합성 값이며 실제 유료 실패 원인이 아니다.**

과금 없는 앱 연결에서는 검색→본문 읽기→확정→답변 인용 검증 completed와 고정 색인의 추가 수집 차단 error를 확인했다. 앱 전체 JUnit 585건·게이트웨이 10건·자료 계약 1건과 bootJar가 통과했다. 공급자 연동 성공을 뜻하지 않는다.

실행 자료는 Git 제외 `build/rag-prep/runs/tool-comparison-20261110T002210Z/`에 보존했다. identity.json에 커밋·브랜치·dirty 파일 목록·JAR/자료 SHA-256, probe.json에 단계별 시각·종료 이벤트, requests-all.jsonl과 전후 ledger에 공급자 시도/usage/예약, backend.log·gateway.log에 실행 증거가 있다. 이전 자료는 `build/rag-prep/runs/rag-live-20261029T083706Z/`에 그대로 유지했다.

이번 비교는 이전과 같은 무저장 NDJSON·고정 색인·리랭커 후보 15개 경로다. searchLaws/collectLaw는 고정 색인에서 등록하지 않는다. 새 법령 수집·색인 및 후속 판례 검색의 유료 비교는 포함하지 않는다. 도구 교체 커밋도 후속 판례 흐름은 변경하지 않는다. 로컬 리팩토링·질문 프롬프트·실효 옵션과 실행 시점/공유 자원이 달라 순수 Tool 교체만의 A/B 실험으로 해석하지 않는다.

재현 도구는 `scripts/load-testing/run-tool-comparison.ps1`이다. 미정산/중단 ledger에서는 추가 유료 실행을 거절한다. 공급자 오류 원인과 미정산 요청을 확인한 뒤에야 유료 재측정이 가능하다.
