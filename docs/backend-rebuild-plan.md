# 백엔드 재구축 계획 요약

최신화: 2026-10-13. 이 문서는 결정 사항과 작업 순서를 안내한다. 세부 설계는 아래 주제별 계획을 따른다. **계획은 구현 완료를 의미하지 않는다.**

## 문서 안내

| 읽을 목적 | 문서 |
| --- | --- |
| 법령 수집·페이지 탐색·청킹·Ollama·Qdrant·근거·Advisor | [RAG·ETL 상세 계획](plans/rag-etl.md) |
| Redis Access·MySQL Refresh·쿠키·회전·장애 복구 | [인증·보안 실행 계획](plans/auth-security.md) |
| 에이전트 역할·모델·추론 수준·예산·도입 판단 | [오케스트레이션·모델 계획](plans/orchestration-models.md) |
| 구현 순서·완료 조건·평가·운영·전환 | [구현·검증 계획](plans/delivery-validation.md) |
| 기존 API·법령 검색 동작 | [재구축 전 기준선](backend-baseline.md) |
| 인증 코드의 기존 문제와 설계 근거 | [JWT 보안 검토](jwt-security-review.md) |
| 로컬 실행·컨테이너 검증 | [인프라 가이드](../docker/README.md) |
| 이번 분리의 보완점·미결정 항목 | [병렬 검토 결과](plans/review-notes.md) |
| 법령 API 장애 시 보조 검색 후보 | [SerpApi 활용 검토](plans/search-fallback.md) — 미확정 |
| 구현 기능·스키마·검증·다음 담당 작업 | [개발 인수인계](development-handoff.md) |

## 확정한 방향

- **MySQL + Qdrant + Redis:** 업무·원문·대화·근거·Refresh는 MySQL, 벡터는 Qdrant, Access 활성 상태는 Redis가 담당한다.
- **Spring AI ETL부터 개발:** Reader → Transformer → Qdrant writer. MySQL 원문에서 재색인할 수 있게 만든다.
- **법령·판례 API는 Tool로 구성:** 기존 API 클라이언트·수집 서비스 위에 목록/상세 Tool 어댑터를 둔다. 초기에는 모델의 구조화된 검색 제안을 서버가 검증·실행하며 후속 상담에는 Tool을 제공하지 않는다. [Tool 계약](plans/rag-etl.md#법령판례-api-tool-구성-확정)을 따른다.
- **최초 질문은 필요한 수집 허용:** 기존 검색 후 부족하면 다음 페이지·다른 법령을 수집하고 같은 요청의 ETL·재검색에 반영한다. 고정 2라운드 제한 대신 진행 여부와 시간·호출·토큰 예산으로 종료한다.
- **후속 질문은 추가 검색 없음:** 최초 근거 스냅샷과 MySQL 기록을 MessageChatMemoryAdvisor로 전달한다. 새로운 쟁점은 한계를 알리고 새 상담을 안내한다.
- **인증 상태 관리:** 짧은 Access + Redis 식별자/TTL, Refresh는 MySQL 별도 테이블에 해시·회전·폐기 이력을 저장한다. Redis 장애 시 인증을 우회하지 않는다.
- **멀티 에이전트는 기본 RAG 이후:** 서버가 권한·도구·상태·예산·검증·저장을 통제하고 검색 계획·근거 검토 역할을 순서대로 추가한다.
- 기존 API는 우선 유지한다. Refresh 응답 제거·쿠키 정책·장시간 요청 처리의 계약 변경은 프론트와 함께 반영한다.

## 현재와 목표

| 상태 | 범위 |
| --- | --- |
| 구현된 기반 | Spring AI 통합·저장소 프로필·H2 테스트, 법령/판례 내부 Tool·검색 필터·원문 스냅샷, 세 저장소 Compose·CPU Ollama 테스트 환경 |
| 앞으로 구현 | ETL·영속 수집 재개·Spring Ollama/Qdrant 검색·상담방 근거·Advisor·Redis JWT·Refresh·오케스트레이터 |
| 검증 한계 | 기존 테스트/인프라 검증 이력은 새 기능의 구현 완료나 이번 실행 결과가 아님 |

## 실행 순서

1. API·권한 기준선과 긴급 인증 보안 개선, 환경·명시적 스키마 준비.
2. 다중 페이지 수집과 ETL 구현, Ollama 임베딩 비교·선정.
3. Redis Access·MySQL Refresh와 인증 경로 통합.
4. Qdrant 검색·최초 질문 상태·판례 검증·근거 스냅샷 연결.
5. 기본 RAG·출처 검증·Advisor 후속 상담과 기록 복원.
6. zero-shot 기준선에서 one-shot·step-back 품질 비교.
7. 검색 계획·근거 검토 에이전트와 역할별 추론 수준 평가.
8. 기존 경로 제거, API·복구·배포 문서 정리.

완료 조건은 [구현·검증 계획](plans/delivery-validation.md)의 단계 표에서 관리한다. 상담 API 통합 전 인증 저장소와 권한 검증을 완료한다.

## 구현 전에 정할 항목

- 법령 분야·시행일 범위·수집 완료 판정, 오래된 근거와 기존 상담방 처리.
- 법령 API 장애 시 SerpApi 보조 검색 도입 여부. 공식 본문 확보·버전 검증·비용을 평가하며 검색 스니펫은 답변 근거로 사용하지 않는다.
- Ollama 모델·차원·청킹·활성 컬렉션 전환: `bge-m3`, `qwen3-embedding:0.6b`, `embeddinggemma` 비교.
- 요청 전체 예산·근거 충분성·동기/비동기 응답 계약, 중복·취소·중단 복구.
- Access 전달 방식·수명, Refresh 형식·만료·다중 기기·동시 회전 정책.
- Advisor 저장 계약·입력 윈도우, 역할별 모델·추론 옵션 호환성과 품질 기준.

모델·수치는 실험 제안이다. JWT에 상담방 목록을 넣는 방안은 보류하며 기본 구현 범위에서 제외한다.

## 문서 관리 원칙

요약에는 확정 방향·현황·순서만 유지하고 세부 결정은 해당 주제 문서에서 갱신한다. 변경 PR에는 계획/구현/검증 상태와 미결정 정책을 구분한다. 스킬은 반복 가능한 작업 절차가 구현·검증된 뒤 별도로 추출한다. AGENTS.md는 수정하지 않는다.

분리 전 원문은 [2026-10-12 보관본](archive/backend-rebuild-plan-2026-10-12.md)에 남긴다. 보관본은 이력이므로 최신 실행 지침으로 사용하지 않는다.
