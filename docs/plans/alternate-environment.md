# 별도 개발 환경과 리랭커 검토

2026-10-24 사용자 선택: 단일 벡터를 유지하고 실행 환경을 분리하며,
법령 목록과 조문 검색 두 단계의 리랭커를 함께 검토한다.
브랜치는 `environment/alternate-development`이며 장치명이나 특정 GPU 제품에 결합하지 않는다.

## 단일 벡터 기준선

KURE-v1 고정 revision, 1024차원 float32, Cosine, 300 코드포인트 청크와 64 중첩을 유지한다.
CPU/CUDA는 실행 설정으로 선택하고 CUDA가 없으면 묵시적으로 CPU로 전환하지 않는다.
배치·스레드·GPU 장치 번호·메모리·CPU 한도는 환경변수로 선택한다.
모델 캐시는 Compose named volume에 보존하고 특정 PC의 선행 이미지/외부 볼륨을 요구하지 않는다.
GPU에서도 float32와 정규화를 유지하지만 CPU와 비트 단위 일치는 보장하지 않는다.

현재 벡터 원시 저장량은 청크 수 × 1024 × 4바이트다.
10만/50만/100만 청크에서 각각 0.38/1.91/3.81GiB이며 MySQL 원문, Qdrant payload/HNSW/WAL,
재색인 신구 세대와 백업은 별도다. 전체 법령 청크 수는 아직 실측하지 않았다.
다중 벡터 도입은 현재 범위에서 제외한다.

## 리랭커 두 단계

### 법령 목록: 상세 수집 전에 평가

현재 `LawCandidateSelector`는 LLM으로 모든 검색 페이지의 목록 후보를 평가하고,
`LegalEvidenceCollector`는 전체 페이지 평가 후 70점 이상 상위 2개를 상세 수집 대상으로 남긴다.
본문이 없는 목록을 로컬 cross-encoder로 평가한 결과를 조문 본문 관련성과 동일하게 해석하지 않는다.

우선 누락 사례를 포함한 고정 질문셋에서 현행 70점/2개와 점수·개수 대안을 비교한다.
페이지별 점수 일관성, 여러 법령이 함께 필요한 질문, 법령명/시행일을 검증하고,
선정 파라미터는 구현 시 설정으로 노출해 `LawCandidateSelector`와 collector 양쪽이 공유하게 한다.
실측 없이 점수·개수를 임의로 높이거나 LLM 평가를 교체하지 않는다.

### 조문 검색: dense 후보 검색 뒤 본문 재정렬

현재 `LawRagSearchToolService`와 `LawVectorClient`는 조문별 최고 청크를 묶어
유사도순 서로 다른 조문 15개를 반환한다. 리랭커는 이 15개만 재배열하면
후보 검색에서 누락된 조문을 복구할 수 없으므로 후보 확장과 함께 비교해야 한다.

제안 실험은 dense 후보 조문 30/50/100개 → 로컬 cross-encoder 재정렬 → 서로 다른 조문 상위 15개다.
후보 확장 수와 최종 개수는 별도 설정으로 둔다. 한국어 품질은 법령 평가셋으로 확인한다.
우선 후보 모델은 다국어 `BAAI/bge-reranker-v2-m3`이며 최종 채택은 아니다.
벡터를 추가 저장하지 않으며 질문/본문 쌍에 대한 추론 비용과 GPU 메모리가 증가한다.

원문은 서버에서 검증한 조문만 사용하고 COMPLETE 세대·fingerprint·법령/시행일·명시 조문 필터,
출처/인용 권한 검증을 유지한다. 긴 조문은 조용히 잘라내지 않고 길이 제한·분할 집계 정책을 평가한다.
리랭커 점수와 기존 Cosine 점수는 다른 척도이므로 기존 임계값을 그대로 적용하지 않는다.
오류 시 dense fallback 또는 요청 실패 정책을 명시하고 고장 상태를 숨기지 않는다.
후속 대화에서 수집/ETL을 새로 허용하는 변경으로 확대하지 않는다.

## 자원과 검증

KURE와 리랭커를 GPU에 함께 상주시킬지는 실제 VRAM peak를 측정해 결정한다.
프로세스별 잠금만으로 둘의 동시 GPU 메모리 사용을 제어할 수 없으므로,
공유 추론 대기열 또는 제한된 순차 로딩도 비교한다. 장치 사양을 코드 상수로 두지 않는다.

구현 시 `law.client`에 외부 HTTP 경계, `law.service.retrieval`에 조정,
`law.dto.retrieval`에 내부 결과를 두고 법령 목록과 조문 점수 계약을 분리한다.
정답 법령 선정 Recall, 조문 Recall@15/MRR/nDCG, p50/p95, 입력 길이,
VRAM/RAM, API 비용과 실패 사례를 기준선과 비교한다.

현재 환경의 법령 목록 리랭커는 사용자 요청으로 최소 60점·최대 3개로 완화했다.
`APP_LAW_CANDIDATE_MINIMUM_SCORE`와 `APP_LAW_CANDIDATE_MAX_CANDIDATES`로 조정하며,
공통 기본값은 70점·2개다. 모델 프롬프트에도 같은 선택 정책을 전달한다.
전체 페이지 평가 후 선별하고 법령 한 건의 색인 후 재검색하는 흐름은 유지한다.
직전 시간 초과 요청은 조건당 후보가 한 건씩이었으므로 이 완화가 해당 실패를 해결한다고 단정하지 않는다.
조문 검색 cross-encoder는 [구현 계약](../law/article-reranking.md)으로 연결한다. 실제 법률 품질 평가는 남아 있다.
환경 변경의 검증 결과는 [검증 현황](../validation/validation-status.md)에 기록한다.

## 공식 참고

- [KURE-v1 모델 카드](https://huggingface.co/nlpai-lab/KURE-v1)
- [리랭커 후보 모델](https://huggingface.co/BAAI/bge-reranker-v2-m3)
- [PyTorch CUDA 설치 버전](https://pytorch.org/get-started/previous-versions/)
- [Docker Desktop GPU 지원](https://docs.docker.com/desktop/features/gpu/)
