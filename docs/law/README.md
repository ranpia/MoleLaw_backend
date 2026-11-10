# 법령·판례·RAG

법령·판례를 검색하고 원문을 보존해 상담 답변의 근거를 제공한다.

기본 QDRANT 경로는 질문 분석·조문 검색·본문 읽기·충분성 판단을 실행하고, 필요하면 법령을 수집해 MySQL 원문과 검증된 벡터 색인을 저장한다. 증분 색인과 선택형 조문 리랭커를 지원하며 동의 기반 후속 판례 검색은 전체 목록 수집·본문 검토·인용 근거 읽기를 수행한다. local 전용 ETL·검색 비교 API도 제공한다.

자동 개정 감시·영속 작업 재개·요청 전체의 실제 LLM usage 비용 차단은 미구현이다. AI_SEARCH 전환 코드는 있지만 제공자의 정상 실응답과 검색 품질은 미검증이므로 기본 경로는 QDRANT를 유지한다. 완료 색인·원문·인용 검증만으로 법적 적용 가능성이나 답변 정확성이 보장되지는 않는다.

- [조문 API Tool과 직접 구현한 RAG 비교](ai-search-comparison.md)
- [Qdrant 조문 검색 리랭커](article-reranking.md)
- [판례 검색 범위·전체 페이지·최종 답변 분리](case-retrieval-reranking.md)
- [최초 법령 검색·수집·본문 읽기 LLM 도구](law-retrieval-tools.md)
- [근거 수집 LLM 판단 계약](evidence-decision.md)
- [후보 목록과 조문 본문 읽기](evidence-reading.md)
- [증분 색인과 조문 메타데이터](incremental-law-indexing.md)
- [법령 ETL과 로컬 테스트 API](law-etl-local.md)
- [법령 벡터화와 Top 15 검색 도구](law-vector-tools.md)
- [법령·판례 API Tool](legal-api-tools.md)
- [질문 분류와 검색 계획](question-routing.md)

시험 자료는 [RAG 데이터셋](../testing/rag-dataset.md), [임베딩 처리 한계](../testing/embedding-capacity.md), [리랭커 처리 한계](../testing/reranker-capacity.md), [혼합 부하](../testing/mixed-rag-capacity.md), [고정 색인 전체 RAG 준비](../testing/full-rag-preparation.md)에서 확인한다. 고정 색인 시험의 추가 수집 차단과 게이트웨이 비용 제한은 운영 경로 전체의 usage 예산 구현과 구분한다.

[전체 문서](../README.md) · [프로젝트 README](../../README.md)
