# Qdrant 조문 검색 리랭커

법령 목록에서 수집 대상을 고르는 LLM 리랭커와 별도로, 이미 저장된 조문 검색에 로컬 cross-encoder를 적용한다.

## 흐름과 보존할 계약

KURE 단일 벡터 검색 → 서로 다른 조문 후보 최대 50개 → 완료 세대/원문/시행일/명시 조문 필터 검증 →
질문과 각 조문의 대표 검색 청크를 cross-encoder로 비교 → 상위 15개 반환 순서다.
후보가 부족하면 있는 만큼 반환한다. 조문 전체를 임의 절단해 넣지 않고 제목과 검색된 청크 본문을 사용한다.
최종 LLM에는 기존 전체 조문 본문과 출처를 그대로 제공한다.

`LawRerankerClient`는 모델/revision, 후보 수, 정확한 ID 집합, 중복/누락/추가 ID와 유한 점수를 검증한다.
랭킹만 변경하고 `LawSearchEvidence.score`는 기존 Cosine 점수로 보존한다.
여러 질의는 기존 RRF가 리랭크된 순위를 사용한다. 두 점수를 같은 척도로 해석하지 않는다.
오류/timeout은 요청 실패로 드러내며 dense 결과로 조용히 fallback하지 않는다.

벡터·컬렉션·기존 색인을 변경하거나 추가 벡터를 저장하지 않는다. 재색인은 필요 없다.
원문 수집/ETL·최초/후속 권한 경계를 새로 열지 않는다.

## 실행 설정

공통 기본값은 비활성화이며 별도 개발 환경 `.env`에서 활성화한다.

| Spring 환경변수 | 기본값 |
| --- | --- |
| `APP_LAW_RERANKER_ENABLED` | false |
| `APP_LAW_RERANKER_BASE_URL` | http://localhost:11437 |
| `APP_LAW_RERANKER_MODEL` | BAAI/bge-reranker-v2-m3 |
| `APP_LAW_RERANKER_REVISION` | 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e |
| `APP_LAW_RERANKER_CANDIDATE_LIMIT` | 50 (15~100) |
| `APP_LAW_RERANKER_READ_TIMEOUT_MILLIS` | 30000 |

백엔드 Compose는 내부 주소 `http://kure:11434`를 주입한다. 서버 주소를 소스에 PC별로 고정하지 않는다.
`docker/kure/.env`의 `KURE_RERANKER_ENABLED=true`도 함께 설정한다.
`KURE_RERANKER_MODEL`, `KURE_RERANKER_REVISION`은 Spring 설정과 맞춘다.
내부 추론 배치는 `KURE_RERANKER_BATCH_SIZE`(기본 2), 질문/문서 쌍 최대 토큰은
`KURE_RERANKER_MAX_TOKENS`(기본 1024, 허용 128~8192)다.
초과 입력은 400으로 거절하며 조용한 truncation은 사용하지 않는다.

```powershell
docker compose --env-file docker/kure/.env -f docker/kure/compose.yml -f docker/kure/compose.gpu.yml up -d --build
```

Python 어댑터의 `/api/rerank`는 내부 모델 HTTP 계약이며 외부 사용자용 Spring API가 아니다.
KURE와 모델 캐시·추론 잠금·유한 대기열을 공유해 GPU에서 동시 추론하지 않는다.
KURE는 float32를 유지하고 cross-encoder는 CUDA에서 float16, CPU에서 float32가 기본이다.
직접 실행할 때 `RERANKER_DEVICE`와 `RERANKER_DTYPE`으로 선택할 수 있다.
모델은 시작 시 로드하며 활성화했는데 GPU/모델 로딩이 실패하면 정상 준비 상태로 표시하지 않는다.

## 예산과 검증

리랭크 HTTP는 실행당 기존 retrieval 호출 예산에서 차감하고 호출 전/후 처리 마감시간을 검사한다.
GPU 대기·추론 시간은 일반 처리 시간에 포함한다. 현재 3분 일반 처리 예산을 늘리거나
리랭크 시간을 무상 제외하지 않는다. 상세 개선안은 [예산 검토](../plans/retrieval-budgets.md)를 따른다.

단위/HTTP 회귀는 순서 변경·top 15·dense 점수 보존·필터/미완료 세대 차단·응답 오류·늦은 응답 거부를 검증한다.
실제 모델 smoke는 동작 확인이며 법률 관련성의 품질 합격을 뜻하지 않는다.

공식 근거: [모델 카드](https://huggingface.co/BAAI/bge-reranker-v2-m3),
[Sentence Transformers CrossEncoder](https://sbert.net/docs/cross_encoder/usage/usage.html).
