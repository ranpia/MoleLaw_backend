# Qdrant 실험 자료 관리

2026-10-19 기준 네 모델의 300·500·700자, **12개 구성**을 완료했다. 주요 모델 비교는 [그래프](../chunk-experiments/overview.md)와 [전체 지표](../chunk-experiments/comparison.md)를 따른다. 로컬·Qdrant 정확도 비교와 반복 시간 측정은 아래 상세 검증 기록으로 분리한다.

| 자료 | 저장 위치 | 완료 보고서 기준 규모 |
| --- | --- | --- |
| 법령 청크·벡터 | `molelaw_exp_*` | 12개 컬렉션, 84,648개 포인트 |
| 원본·정제 질문 벡터·정답·검색 결과 | `molelaw_eval_queries_*` | 모델별 220개, 총 880개 포인트 |
| 데이터셋·정제본·모델 설정 | `molelaw_eval_catalog_v1` | 8개 기록 |

질문 포인트에는 원문·정제문·정답 조문 ID와 세 청크의 Qdrant 전수 검색 상위 10개, 유사도, 지표를 보존한다. 전체 순위 MRR 원자료도 함께 보존하며 상위 10개만 계산한 MRR@10과 구분한다. 카탈로그는 질문·정제 JSON의 원본 바이트와 SHA-256을 보관한다. 질문 컬렉션은 법령 검색 대상과 분리한다.

```powershell
# 컬렉션 목록과 데이터 검증
.\evaluation\legal-retrieval\scripts\qdrant-lab.ps1 -Action List
.\evaluation\legal-retrieval\scripts\qdrant-lab.ps1 -Action Verify -Model qwen3-0.6b -Size 300

# 카탈로그·질문·결과 멱등 저장
.\evaluation\legal-retrieval\scripts\store-qdrant-catalog.ps1
.\evaluation\legal-retrieval\scripts\store-qdrant-evaluation.ps1 -Model qwen3-0.6b

# 모델 서버·로컬 벡터 캐시 없이 저장된 질문 벡터로 재검색
.\evaluation\legal-retrieval\scripts\search-qdrant-evaluation.ps1 -Model qwen3-0.6b -QueryId civil-2 -QueryMode rewritten -Size 500
```

새 질문의 임베딩에는 모델 서버가 필요하지만, 저장된 평가 질문의 재검색에는 Qdrant만 필요하다. 저장·검증은 컬렉션 소유자, 모델 digest, 차원, 입력 형식, 데이터셋 해시를 확인한다. Git의 결과는 공유·재현용이며 로컬 캐시는 재추론 방지용이다. API 키는 환경변수 또는 Git 제외 설정에서 읽고 문서·로그에 노출하지 않는다.

12개 구성 × 원본·정제 220개 = 2,640개 질문 실행 조합을 검증했고, 반복 시간은 총 15,840개 표본이다. 임베딩·LLM 정제·답변 생성은 시간에서 제외한다. 단일 PC의 순차 측정이므로 동시 부하 성능이나 모델 자체의 추론 속도를 뜻하지 않는다. 작은 세그먼트에는 전수 탐색이 사용될 수 있어 ANN 설정만으로 매번 HNSW가 실행됐다고 단정하지 않는다.

[Qwen3 실행 환경](../../../../docker/qwen3/README.md)과 [검색 평가 실행 안내](../../README.md)를 참고한다.

## 이전 단계의 검증 기록

아래는 Qwen3 추가 전부터 남긴 단계별 검증과 명령의 기록이다. 당시 컬렉션 수·측정 수치를 현재 전체 규모로 읽지 않는다.

아래 수치와 세 모델 기준 설명은 Qwen3 추가 전 검증 기록이다. 기존 세 모델의 300·500·700문자 총 9개 구성을 완료했다. 컬렉션은 9개, 포인트는 총 63,486개이며 고정 질문의 원본·정제 1,980개 실행 조합에서 검색 시간을 11,880회 측정했다. [모델·청크·질문 정제 통합 비교](../chunk-experiments/comparison.md)에서 모든 크기를 함께 볼 수 있다. 아래의 기존 700문자 기록도 보존한다.

300·500문자 실험은 `-Size 300` 또는 `-Size 500`으로 실행한다. 크기를 생략하면 기존과 같이 700문자를 사용한다. 원문·질문·정답·분할의 동일성을 검증한 뒤 크기별 컬렉션과 결과 폴더를 분리한다.

```powershell
# 한 모델의 500·300문자 정제 평가·색인·검증·Qdrant 시간 측정을 순차 실행
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-chunk-experiments.ps1 -Model kure-v1

# 모든 모델 완료 후 크기별 비교를 전체 모델로 다시 집계
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-qdrant-benchmark.ps1 -Size 300
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-qdrant-benchmark.ps1 -Size 500
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-chunk-experiments.ps1
```

정제 질문은 700문자 평가에 쓴 원문과 동일한 질문을 사용한다. 청크마다 데이터 파일 해시는 다르지만 정규화한 원문·질문·정답·분할 identity와 각 원본 질문을 함께 검사한다. 모델 추론·색인·시간 측정은 순차 실행해 메모리 부담과 측정 간섭을 줄인다. 작은 세그먼트 일부는 색인 임계값보다 작아 전수 탐색 대상으로 남을 수 있으므로, 모든 포인트가 HNSW에 들어가야 한다고 가정하지 않는다.

전체 질문 비교는 [Qdrant 검색 벤치마크](benchmark/comparison.md)에 기록한다. 모델별 원본 110개·정제 110개에 같은 질문 벡터를 사용하고 기존 전수 계산·Qdrant 전수·근사 검색을 비교한다. 질문 임베딩을 제외한 검색 시간은 각 방식 3회씩 측정한다.

기존 세 모델의 700문자 질문 비교 기록: 총 660개 질문 변형·3,960개 검색 시간 표본이다. 모든 질문에서 Qdrant 전수 검색 점수가 기존 계산과 허용 오차 이내에서 일치했고, 근사 검색으로 Recall@5가 바뀐 질문은 없었다. 상위 10개 조문 집합은 평균 99.55~99.91% 일치했으며 Gemma 정제 질문 1개는 nDCG@10이 달라졌다. 반복 지연시간은 동시 사용자 부하 시험과 구별한다.

로컬 Qdrant를 이용해 기존 법령 벡터를 보관하고 검색하는 실험 환경이다. 별도 Qdrant 컨테이너를 늘리지 않고 이미 실행 중인 인스턴스에 실험용 컬렉션을 만든다. 서비스의 Spring 설정·기본 컬렉션은 변경하지 않는다. 새 컨테이너 이미지나 임베딩 모델 다운로드는 필요하지 않다.

## 들어 있는 자료

| 모델 | 차원 | 청크 | 저장 위치 |
| --- | ---: | ---: | --- |
| EmbeddingGemma | 768 | 700문자 / 중복 64 | `molelaw_exp_embeddinggemma_c700_66106d14_85462619` |
| BGE-M3 | 1024 | 700문자 / 중복 64 | `molelaw_exp_bge_m3_c700_66106d14_79076464` |
| KURE-v1 | 1024 | 700문자 / 중복 64 | `molelaw_exp_kure_v1_c700_66106d14_8b418a58` |

각 컬렉션에는 같은 법령 코퍼스의 청크 6,031개가 들어간다. 벡터를 새로 만들지 않고 이전 평가의 캐시를 사용한다. 차원이 같아도 다른 모델의 벡터는 섞지 않는다. 질문을 정제하더라도 문서 벡터는 같으므로 질문별 컬렉션을 따로 만들 필요는 없다.

각 청크에는 법령 ID, 조문 ID, 제목, 본문, 원문 링크, 원문·본문 해시, 청크 위치와 모델 버전을 함께 저장한다. **원본 파일과 캐시는 재현용 기준 자료**, **Qdrant는 쉽게 검색·조회하기 위한 실험 저장소**로 쓴다.

## 자료 확인하기

Qdrant 대시보드: [http://127.0.0.1:6333/dashboard](http://127.0.0.1:6333/dashboard). 인증이 필요하면 로컬 `docker/.env.compose`의 API 키를 사용한다. 키를 문서·스크린샷·Git에 넣지 않는다. 데이터는 기존 `qdrant_data` Docker 볼륨에 남으므로 컨테이너를 재시작해도 유지된다. 볼륨을 삭제하면 데이터도 사라진다.

```powershell
# 실험 컬렉션과 자료 수 확인
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action List

# 자료 적재 또는 재적재: 같은 ID를 갱신하므로 중복 생성되지 않는다
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Index -Model embeddinggemma
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Index -Model bge-m3
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Index -Model kure-v1
```

컬렉션 이름의 끝은 코퍼스·모델 해시다. 현재 스크립트는 고정된 700문자 평가 데이터만 받는다. 다른 법령 버전이나 청크 실험으로 확장할 때는 별도 컬렉션을 만들고 입력 검증도 함께 갱신해야 한다. 다른 내용의 자료를 기존 컬렉션에 섞지 않는다.

## 질문으로 검색하기

질문 검색에는 기존 Ollama 실험 컨테이너가 필요하다. 처음 한 번 `docker start molelaw-embedding-lab-ollama-1`로 켠다. Spring 서버나 OpenAI 호출은 필요 없다. 기본 Ollama 포트는 11435, Qdrant HTTP 포트는 6333이다.

KURE 질문 검색은 Ollama 대신 공식 모델을 실행하는 어댑터를 사용한다. `docker start molelaw-kure-lab-kure-1` 후 `-Model kure-v1`을 지정하면 기본 포트를 11436으로 선택한다. 공식 revision·라이브러리 버전·float32·CPU 설정이 이전 평가와 다르면 실행을 거부한다. 인덱스 적재나 문서 벡터 검증만 할 때는 어댑터가 필요 없다.

```powershell
# BGE-M3로 상위 조문 5개 검색
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Search -Model bge-m3 -Query '회원 탈퇴 후에도 개인정보를 보관하고 있어요' -Limit 5

# 전수 검색: 속도보다 기준 결과 확인이 목적일 때 사용
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Search -Model embeddinggemma -Query '휴게시간을 전혀 주지 않았어요' -Limit 5 -Exact

# 특정 법령만 검색: law_id는 코퍼스의 식별자다
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Search -Model bge-m3 -Query '임금을 지급하지 않았어요' -LawId labor -Limit 5
```

결과의 similarity는 코사인 유사도이며 정답 확률이 아니다. 같은 조문의 청크가 여러 개 검색되더라도 대표 청크 하나로 묶어 보여 준다. 본문은 대표 청크이며 조문 전체가 아닐 수 있다. 원문 링크로 전체 내용을 확인할 수 있다.

기본 검색은 Qdrant가 색인을 활용할 수 있는 근사 검색 설정이다. 작은 데이터나 필터 조건에서는 Qdrant가 전수 탐색을 선택할 수도 있어, `-Exact`를 생략했다고 항상 HNSW를 사용했다고 주장하지 않는다. `-Exact`는 전수 탐색을 요청한다. 조문 묶음은 `unit_id`, 법령 필터는 `law_id`의 keyword 색인을 사용한다. [공식 그룹 검색 API](https://api.qdrant.tech/api-reference/search/query-points-groups)를 따른다.

## 검증과 정리

```powershell
# 전체 질문 품질·반복 검색 시간 비교 (자료 재색인 없음)
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/benchmark-qdrant.ps1 -Model embeddinggemma
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/benchmark-qdrant.ps1 -Model bge-m3
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/benchmark-qdrant.ps1 -Model kure-v1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-qdrant-benchmark.ps1
```

질문 벡터는 `artifacts/qdrant-query-cache/`에서 입력 문자열·모델 버전별로 재사용한다. 같은 캐시를 재사용해 반복하면 임베딩을 새로 생성하지 않으며 검색 결과 파일은 새 측정값으로 갱신된다. 벤치마크의 MRR@10은 10위 밖 정답을 0으로 처리하므로 기존 전체 순위 MRR과 다르다. 수동 질문 검색은 짧은 사용에 맞춰 요청 후 모델을 내리고, 벤치마크는 질문 벡터 생성 중 모델을 유지했다가 생성 완료 후 내린다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-qdrant-lab.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Verify -Model embeddinggemma
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Verify -Model bge-m3

# 실제 질문을 임베딩한 검색까지 검증 기록에 남기려면 -Query를 지정한다 (Ollama 필요)
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Verify -Model bge-m3 -Query '휴게시간을 전혀 주지 않았어요'

# 해당 모델의 현재 실험 컬렉션만 삭제할 때 실행한다
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Delete -Model embeddinggemma
```

색인·검색·삭제 전에 컬렉션의 실험 소유자, 모델 해시, 코퍼스 해시, 차원, 거리 함수를 검사한다. 소유자가 다른 컬렉션은 변경하지 않는다. 적재 도중 중단된 컬렉션은 자료 수가 맞지 않으면 검색을 거부하며 `Index` 재실행으로 복구할 수 있다. 인증 키는 환경변수 `QDRANT_API_KEY`, 없으면 `docker/.env.compose`, 그다음 루트 `.env`에서 읽는다. 접속 호스트는 로컬로 한정한다.

`Verify`는 문서 벡터 3개를 사용해 저장 벡터·원문의 왕복 확인, 기존 전수 코사인 계산과 Qdrant의 상위 10개 점수 비교, 조문 묶음과 법령 필터를 검사한다. 동점이면 같은 점수의 조문 순서가 다를 수 있어 점수 일치와 근사 검색 조문 겹침을 별도로 기록한다. 모델별 `*-verification.json`에 이 간단 검증 기록을 보존한다. 전체 질문 품질·반복 지연시간은 별도 `benchmark/` 결과를 사용한다.

2026-10-19: Gemma·BGE-M3 각각 6,031개, 합계 12,062개 벡터 적재 완료. 두 모델의 문서 벡터 검증과 실제 질문 검색을 확인했다. Gemma는 재적재 후에도 6,031개로 유지되어 중복 방지를 확인했다. 기존 Qdrant에는 컬렉션이 없었으며 이번 작업에서는 실험 컬렉션만 생성했다. 전체 평가 지표는 [원본 질문 비교](../comparison.md), [정제 질문 비교](../query-rewrite-v1/comparison.md)를 참고한다.

이어서 KURE-v1 6,031개를 추가해 총 18,093개를 세 컬렉션에 보관한다. KURE의 원본·정제 질문에서 전수·근사 검색의 상위 10개 조문은 각각 99.82%·99.91% 일치했고 질문별 Recall@5는 모두 유지됐다. 실험 후 KURE 어댑터는 중지하고 모델 볼륨·캐시·Qdrant 컬렉션은 보존한다.
