# 패키지·DTO·API 배치

기준: 2026-11-09. 현재 Java 최상위 패키지는 controller/service/config/dto/dao/entity/util이며, 이전 도메인 우선 구조의 규칙을 대체한다. [폴더 트리](folder-structure.md)를 참고한다.

## 개편 이후 추가된 현재 API

| 소유 컨트롤러 | 경로·역할 |
| --- | --- |
| InitialQuestionSuggestionController | POST /api/legal-answers/suggestions: 최초 질문 방향 추천 |
| InitialAnswerStreamController | POST /api/legal-answers/stream, /api/chat-rooms/stream: 최초 SSE |
| InitialAnswerStreamController | POST /api/legal-answers/ndjson, /api/chat-rooms/ndjson: 최초 NDJSON |
| InitialAnswerStreamController | POST /api/chat-rooms/{roomId}/messages/ndjson: 후속 NDJSON |
| ChatRoomController | PATCH /api/chat-rooms/{roomId}/title: 상담방 제목 변경 |
| ChatEvidenceController | GET /api/chat-rooms/{roomId}/evidence 및 /{evidenceId}: 현재 근거 카드·본문 |
| ChatEvidenceController | DELETE /api/chat-rooms/{roomId}/evidence/{evidenceId}: 방별 근거 제외 |

이 경로들은 인증이 필요하며 POST·PATCH·DELETE는 CSRF를 검사한다. 상세 요청·이벤트·저장 계약은 [상담 문서](../chat/README.md)를 따른다. 과거의 공개 API 20개 검증 수치를 현재 전체 엔드포인트 수로 사용하지 않는다.

## 이동 기준

| 이전 배치 | 현재 배치 |
| --- | --- |
| auth/user/chat/law.controller | controller |
| 각 도메인 service | service 및 역할별 하위 패키지 |
| 각 도메인 config·common.ai.config | config |
| auth.security의 필터·OAuth 성공 핸들러 | config.security |
| auth.security의 인증/토큰 서비스 | service.security |
| auth.oauth의 사용자 조회 | service.oauth |
| common.ai | service.ai |
| law.client | service.client.openlaw/vector |
| 각 도메인 dto | dto.request/response 및 내부 계약별 하위 패키지 |
| 각 도메인 repository·Refresh 저장소 | dao |
| 각 도메인 entity | entity |
| 파싱·암호화·JWT·쿠키 보조 처리 | util |
| 예외 타입·오류 코드·입력 validator | util.exception/validation |
| GlobalExceptionHandler | controller |
| ErrorResponse·ApiResponse | dto.response |

같은 패키지에서 생략했던 import도 이동 목적지에 맞췄다. 테스트는 구현 위치를 따르며 여러 기능의 설정 통합 검증은 config에 있다. Gradle 전용 테스트 필터·로거 클래스명·simulation 스크립트·문서의 클래스/소스 참조를 함께 변경했다. 엔티티의 클래스 이름과 테이블, 외부 API/JSON 계약 및 실행 리소스는 유지한다.

## 컨트롤러와 오류

AuthController는 가입·로그인·로그아웃·Refresh를, CsrfController는 토큰 조회를, UserController는 프로필·계정 관리를 맡는다. ChatRoomController·LegalAnswerController·InitialAnswerStreamController는 상담방·무저장 답변·스트림을 담당하고, 법령·판례·키워드·local 진단 API도 controller의 해당 컨트롤러에 둔다. 기능 실행은 service, JPA/Redis 저장은 dao가 담당한다.

- 도메인 컨트롤러 12곳의 @ExceptionHandler가 도메인 오류 메시지·상태·본문을 생성한다. GlobalExceptionHandler는 공통 입력·미처리 오류, 인증/CSRF/CORS/Origin/OAuth 등 MVC 밖의 오류 및 공통 오류 로그를 맡는다.
- 서비스는 예외를 전파하며 실제 업무 검증·checked exception 변환·복구·재시도·취소·자원 정리용 catch만 유지한다. ChatService·LegalAnswerService·AiChatService의 포괄적 API 오류 포장, LawSearchService의 통신 오류 포장 및 ETL 서비스의 HTTP 오류 변환은 제거했다.
- 일반 오류는 application/json의 success:false와 error:{status,code,message}를 반환한다. 405의 Allow 같은 프레임워크 오류 헤더를 보존한다. 전송 중인 SSE/NDJSON은 HTTP 상태를 덮어쓰지 않고 공통 분류의 error 이벤트로 종료한다.
- MolelawException은 오류 코드·선택적 상세 안내·원인을 운반하는 최소 예외다. 별도 메시지 필드와 getMessage 재정의는 제거했다. Java 로그는 @Slf4j이며 예외 메시지·키·토큰·질문·공급자 응답을 그대로 출력하지 않는다.
- 인증 실패는 예전 {"error":"Unauthorized"} 대신 공통 UNAUTHORIZED JSON, CSRF/CORS/Origin 거절은 FORBIDDEN JSON이다. 이 오류 응답 변경은 패키지 이동 전의 별도 커밋에 포함된다. 패키지 이동 자체는 API 계약을 바꾸지 않는다.

## 검증

오류 처리 변경의 전체 테스트 558개 및 최종 OpenAPI/로컬 컨트롤러 19개와 bootJar가 통과했다. 패키지 이동 후의 별도 검증 기록은 [검증 현황](../validation/validation-status.md)을 따른다. Swagger UI 브라우저 수동 검사나 실제 유료 상담 결과를 대역 테스트 결과로 간주하지 않는다.
