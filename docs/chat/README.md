# 상담·대화

질문에 대한 법률 답변을 생성하고 상담방·대화·확인 근거를 보관한다.

최초 상담은 검색·근거 판단·답변 검증을 마친 뒤 방·근거 스냅샷·암호화된 대화를 함께 저장한다. 추천 질문과 무저장 답변도 제공하며, SSE·NDJSON으로 진행 단계·근거 미리보기·답변 조각을 전달한다. 후속 상담은 방 근거와 대화를 복원하고 이번 요청에서 동의한 경우 새 판례를 보강한다. 근거 목록·본문 조회와 삭제, 방 제목 변경을 지원한다.

후속 상담은 새 법령을 수집·색인하지 않는다. 연결 종료 후 작업 재개와 동시 후속 요청 직렬화는 미구현이며 UUID 인용 검증은 법률 결론의 정확성을 보장하지 않는다. 근거 미리보기는 저장 전 상태이므로 최종 완료 결과로 확정해야 한다.

- [채팅방 생성과 제목 변경](chat-room-management.md)
- [최초 상담 추천 질문](initial-question-suggestions.md)
- [상담 저장·암호화·조회 계약](chat-storage.md)
- [후속 상담과 판례 보강](follow-up-consultation.md)
- [상담 SSE·NDJSON과 실시간 근거 이벤트](initial-answer-stream.md)
- [최초 상담 실행](initial-consultation.md)
- [상담방 근거·인용 검증·사용자 삭제](room-evidence.md)

실행 결과와 검증 범위는 [검증 현황](../validation/validation-status.md), [답변 생성 결과](../testing/answer-generation-results.md), [고정 색인 전체 RAG 첫 상담](../testing/full-rag-first-live.md)에 정리한다.

[전체 문서](../README.md) · [프로젝트 README](../../README.md)
