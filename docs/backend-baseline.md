# 재구성 전 동작 기준선

## 채팅 API 계약

현재 `ChatController`와 DTO 기준이며 이 문서 추가로 API를 변경하지 않는다. 인증된 사용자의 상담방 소유권을 서비스에서 검사한다.

| 요청 | 입력 | 성공 응답 |
| --- | --- | --- |
| `POST /api/chat-rooms/first-message` | `{"content":"상담 질문"}` | 200, `{"id":1,"messages":[...]}` |
| `POST /api/chat-rooms/{roomId}/messages` | `{"content":"후속 질문"}` | 200, BOT 메시지 객체 |
| `GET /api/chat-rooms/{roomId}` | 상담방 ID | 200, 메시지 객체 배열 |
| `DELETE /api/chat-rooms/{roomId}` | 상담방 ID | 204 |

메시지 객체는 `sender`, `content`, `timestamp`를 제공한다. 최초 응답에는 USER·BOT·INFO 메시지가 포함되며 INFO는 법령·판례 안내 마크다운이다. 후속 응답은 BOT 메시지 하나다. 내부 `GptAnswerResponse`의 `answer`·`info`는 HTTP 응답 DTO와 구분한다.

현재 최초 질문은 키워드 추출·상담방 생성·법령 검색·답변을 수행하고, 후속 질문은 최초 BOT 답변과 현재 질문을 모델에 전달한다. `ChatClient` 전환 후에는 이전 답변을 assistant, 현재 질문을 user 역할로 전달한다. 후속 법령 검색은 없지만 전체 대화와 검증된 원문 근거를 전달하는 기능도 아직 없다. 재구성 시 외부 응답 형식을 유지하면서 최초 조회 상태·근거 저장·대화 전달을 보강한다.

## 법령 API와 수집 회귀 테스트

`LawSearchServiceTest`는 `WebClient`의 교환 함수를 대체해 외부 HTTP 호출·DB·모델 API 없이 실행한다.

- 제목 + 소관부처 → 본문 + 소관부처 → 제목(부처 없음) → 본문(부처 없음) 순서와 첫 결과에서 종료.
- `/DRF/lawSearch.do`의 `target=eflaw`, `type=JSON`, `search`, `query`, `org`, `display=20`, `sort=lasc` 검증. 검색 target은 기존 `law`에서 `eflaw`로 변경했으며 실제 API 응답 호환성은 별도 통합 검증 대상이다.
- 목록의 비어 있지 않은 법령일련번호(MST) 추출.
- 외부 API 실패는 예외로 종료하며 무조건 검색을 계속하지 않음.
- `/DRF/lawService.do`의 MST 상세 조회와 법령 노드 누락 시 실패.
- 기존 파서가 지원하는 조문·호·목의 내용 정리와 저장 경로 보존.
- 동일 내용의 청크는 반복 수집 시 다시 저장하지 않음.

`src/test/resources/fixtures/openlaw/law-detail.json`은 필드 구조 검증용 합성 자료다. 실제 API에서 수집한 법령 응답이나 법률 판단·검색 품질 평가 자료가 아니다. 실제 응답 샘플과 평가 질문 30~50개 확보는 남은 작업이다.

```powershell
.\gradlew.bat test --tests com.MoleLaw_backend.service.law.LawSearchServiceTest
```

이 테스트는 기존 파서의 모든 응답 형태 지원을 보장하지 않는다. 배열 형태의 항·단일 객체 형태의 조문 등 지원 범위 점검과 파서 개선은 별도 변경으로 진행한다. 컨텍스트 테스트는 현재 H2 기반 `test` 프로필과 모킹된 모델로 외부 DB·모델 API에서 격리했다.
