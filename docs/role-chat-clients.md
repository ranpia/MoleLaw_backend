# 역할별 ChatClient 설정

## 구성

`AiConfig.baseAiChatClientBuilder`는 기존 `ChatModel`을 공유하는 prototype Builder입니다. 역할별 설정은 받은 Builder를 `clone()`해 구성합니다. 생성 시 모델 API를 호출하지 않습니다.

| Bean | 설정 클래스 | 역할 |
| --- | --- | --- |
| `evidenceCollectionChatClient` | `law.config.EvidenceChatClientConfig` | 쟁점·근거 충분성·추가 검색 조건 검토 |
| `legalAnswerChatClient` | `chat.config.AnswerChatClientConfig` | 최초 답변·대화 메모리 없음 |
| `followUpAnswerChatClient` | 같은 Config에서 최초 답변 Client를 복제 | 후속 상담·MessageChatMemoryAdvisor |
| `chatClient` (`@Primary`) | `common.ai.config.AiConfig` | 키워드 추출의 공통 구조화 호출 |

서비스에는 역할에 맞는 Bean을 `@Qualifier`로 주입합니다. 역할 설정과 프롬프트는 각 도메인의 Config에서 관리합니다. 답변 Client는 `prompts/legal-system.txt`와 공통 인용 지시를 사용합니다. 공통 Builder에는 업무 프롬프트·대화 메모리·수집 Tool을 등록하지 않습니다.

## 옵션

```yaml
app:
  ai:
    evidence:
      temperature: 0.1
      max-tokens: 2048
    answer:
      temperature: 0.3
      max-tokens: 4096
```

모델 이름·접속·인증 설정은 기존 ChatModel 설정을 공유합니다. temperature와 max-tokens는 Client 기본값이며 실제 호출에서 재정의할 수 있습니다. 모델별 지원 옵션은 실제 제공자 계약에 맞춰야 합니다. temperature는 0~2, 출력 토큰은 양수로 검증합니다.

## 연결 범위와 다음 작업

최초 상담은 `InitialAnswerService`, 후속 상담은 `FollowUpAnswerService`가 담당합니다. 후속 Client에만 `MessageChatMemoryAdvisor`가 연결됩니다. 키워드 추출은 공통 구조화 호출을 사용합니다. 최초·후속 모두 인용 ID를 검증하고 방별 최초 근거 스냅샷을 보존합니다. [상담방 근거·기존 방 처리·검증 한계](room-evidence.md)를 따릅니다. 구조화된 근거 수집 판단과 Tool 실행·수집 반복·종료 제어는 후속 구현입니다.

| 클래스 | 책임 |
| --- | --- |
| `law.service.LegalEvidenceCollector` | 기존 상위 3청크 검색·판례 목록 조회·불변 근거 복사 |
| `chat.service.answer.InitialAnswerService` | 최초 답변 생성·인용 검증·결과 조정 |
| 같은 패키지의 `FollowUpAnswerService` | 방별 근거 복원·후속 답변·인용 검증 |
| `EvidencePromptBuilder` | 최초·후속의 근거 및 질문 프롬프트 구성 |
| `EvidenceRenderer` | 서버 출처 마크다운 생성·제목 이스케이프·URL 검증 |
| `PreparedLegalAnswer` | 답변과 사용한 근거를 함께 전달하는 불변 내부 결과 |

기존 `FinalAnswer`·`GptService`는 제거했습니다. 최초 답변의 미사용 `keywordInfo` 인자와 테스트 답변 API의 불필요한 키워드 모델 호출도 제거했습니다. 방 제목을 만드는 최초 상담의 키워드 추출은 유지합니다. 현재 실제 검색은 기존 MySQL 경로이며 독립 Qdrant Tool 연결·수집 반복을 완료한 것은 아닙니다.

같은 정리에서 참조가 없던 `FinalAnswerRequest`, `PrecedentSearchResponse`, `ChatRoomRequest`, 저장소 조회 메서드 5개, 상속된 `findAll` 중복 선언, 미사용 `MinistryCodeMapper.contains`를 제거했습니다. `AiChatService.generateText`, `ChatService`의 EntityManager 주입과 RAG Tool의 미사용 설정 인자도 제거했습니다. API 경로와 응답 JSON 필드는 유지합니다.

근거 수집 Client와 공통 Client에는 대화 Advisor를 붙이지 않습니다. 답변 Client에는 수집 Tool을 등록하지 않습니다.

## 대화 Advisor 계약

- `PersistedChatMemory`가 기존 MySQL `message` 테이블을 읽고 내용을 복호화합니다. 별도 평문 메모리 테이블이나 프로세스 내 대화 캐시는 만들지 않습니다.
- 서버가 인증 사용자·방 ID로 `userId:roomId`를 구성해 `ChatMemory.CONVERSATION_ID`로 전달합니다. 기본 conversation ID에 의존하지 않으며 누락된 ID와 다른 사용자의 방은 거절합니다.
- 메모리 조회 시에도 소유권을 다시 검증합니다. USER/BOT은 각각 user/assistant 역할로 전달하고 INFO는 제외합니다. 최초 질문·첫 답변과 최근 기록을 합쳐 기본 최대 20개를 보냅니다(`app.ai.memory.max-messages`, 4~100). 이는 메시지 수 제한이며 토큰 예산 보장은 아닙니다.
- Advisor의 `add`·`clear`는 의도적으로 무동작입니다. 기록 저장·상담방 삭제는 서버 서비스가 담당합니다. 모델 호출 전에 현재 질문을 DB에 저장하지 않으므로 메모리와 현재 질문이 중복되지 않습니다.
- `ChatTurnPersistenceService`가 인용 검증을 통과한 후속 상담 USER/BOT을 한 트랜잭션으로 저장합니다. 모델 실패·빈 응답·인용 오류는 저장하지 않습니다. 최초 상담도 `InitialChatPersistenceService`가 방·근거·USER/BOT/INFO를 함께 저장합니다.
- 현재 메모리는 방 기록을 읽은 뒤 전송 창을 제한합니다. DB 페이지 조회 최적화, 토큰별 창 제어, 동시 요청 직렬화·중복 방지는 별도 과제입니다. 이전 답변은 대화 맥락이며 근거는 별도의 방별 스냅샷에서 복원해 전달합니다.

## 검증

`RoleChatClientConfigTest`는 실제 ChatClient와 모킹한 ChatModel로 역할 프롬프트·옵션·prototype 격리, 기존 Primary 주입, 설정 오류 시 조기 실패를 확인합니다. 실제 모델 답변 품질은 검증하지 않습니다. 실행: `.\gradlew.bat test`.

`LegalEvidenceCollectorTest`, `InitialAnswerServiceTest`, `FollowUpAnswerServiceTest`와 공통 프롬프트·렌더러 테스트는 근거 복사·판례 실패·인용 검증·실패 응답과 역할별 호출을 확인합니다. `PersistedChatMemoryTest`, `ChatServiceAdvisorTest`는 소유권·기록 창·성공 후 저장을 검증합니다. 외부 모델 API는 모킹합니다. [Spring AI 대화 메모리 계약](https://docs.spring.io/spring-ai/reference/1.0/api/chat-memory.html)을 따릅니다.

Builder API는 [Spring AI 1.0.9 ChatClient 문서](https://docs.spring.io/spring-ai/reference/1.0/api/chatclient.html)를 따릅니다.
