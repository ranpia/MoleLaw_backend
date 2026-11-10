# 코드·모델 구조

controller/service/config/dto/dao/entity/util 계층별 패키지·API 배치·역할별 ChatClient 문서를 모았다.

AI 호출은 질문 분석·후보 평가·본문 읽기·충분성 판단·답변 생성 등 역할별 ChatClient로 나눈다. 후속 답변만 저장된 대화 메모리를 사용하며, 검색 실행 권한과 저장 확정은 서버가 관리한다. 자동 모델 승격·병렬 토론·영속 작업 큐는 구현하지 않았다.

- [폴더 구조 검토](folder-structure.md)
- [패키지·DTO·엔드포인트 정리](package-api-organization.md)
- [클래스 책임 분리·OAuth 클라이언트 교체](class-responsibilities.md)
- [역할별 ChatClient와 모델](role-chat-clients.md)

[전체 문서](../README.md) · [프로젝트 README](../../README.md)
