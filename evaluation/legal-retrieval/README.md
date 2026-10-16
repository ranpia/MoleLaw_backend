# 법령 임베딩 검색 평가

## 후속 모델 평가 계획

2026-10-16 리뷰 반영: 비교의 1차 지표는 development Recall@5, 보조 지표는 MRR·Recall@10·양성/음성 수용률로 정한다. 단독 순차 실행 중 모델·데이터를 고정하고 기존 정상 캐시는 유지한다. 캐시 재사용량이 다른 실행의 색인 시간으로 모델 속도를 비교하지 않는다. 이미 공개된 held-out 결과는 반복적인 선정 기준으로 사용하지 않으며, 이를 보고 설정을 조정했다면 탐색 결과로 표시하고 최종 확인에는 새 독립 질문셋을 사용한다.

정답 unit·그룹·flat chunk 집합과 분할 누수를 검사하고, 비교표는 청크와 무관한 원문·질문·정답·분할 identity 및 보고서 입력 해시·완료 파일을 검증한다. 기존 보고서는 원본 입력 해시가 일치할 때만 identity를 계산해 비교하며 결과 파일 자체를 다시 쓰지 않는다. `scripts/test-evaluation-contract.ps1`로 변조 거절과 variant 간 동일성을 확인할 수 있다.

2026-10-16 재개: 사용자 지시에 따라 보존한 캐시로 두 모델의 300 / 500 / 700문자 평가를 모두 완료했다. [BGE-M3 결과](reports/bge-m3/README.md)와 [EmbeddingGemma 재검토·청크 결과](reports/embeddinggemma/README.md)를 기록했다. Gemma 500문자 재실행은 최초 기준선의 질문 110개 검색 지표와 일치했다. 다음은 사용자 지시로 KURE-v1을 같은 데이터에 평가하는 작업이다. 미완료 구성은 비교표에 포함하지 않는다. 아래 `run-model.ps1` 명령은 저장된 입력 벡터를 재사용한다.

1. 현재 기본 모델 EmbeddingGemma(768차원), BGE-M3(1,024차원)의 300 / 500 / 700문자 청크 비교를 완료한다.
2. 한국어 데이터셋으로 추가 학습한 공개 임베딩 모델을 후보에 추가한다. 한국어 학습 데이터·학습 방법·기반 모델·라이선스·실행 가능 여부를 모델 카드로 확인한 뒤 후보를 확정한다. 자체 추가 학습은 별도 실험으로 구분한다.
3. 확정한 한국어 추가 학습 모델에도 동일한 법령·질문·정답·분할과 청크 조건을 적용한다. 모델별 기본 벡터 차원과 입력 형식을 기록하고, 검색 지표·유사도·처리 시간·저장 용량을 비교한다.
4. 청크와 모델 선택은 개발용 질문으로 진행하고 held-out 질문은 최종 검증에 사용한다. 추가 학습을 직접 진행하는 경우 평가 질문 및 정답 조문이 학습 데이터에 유입되지 않도록 분리한다.

한국어 추가 학습 첫 모델 KURE-v1은 500 / 300문자 평가를 완료하고 사용자 요청으로 종료했다. 700문자는 미평가이며 중단 표식으로 차단했다. 추론 컨테이너는 종료하고 모델·캐시·결과는 보존했다. [KURE 결과](reports/kure-v1/README.md)를 참고한다. 다른 후보와 차원 축소 비교는 후속 실험으로 관리한다. 모델별 검증이 완료되면 각각 커밋·푸시한다.

한국어 모델의 [Hugging Face 후보 조사 기록](review/korean-model-candidates.md)을 보존했다. 진행 브랜치는 `experiment/legal-embedding-evaluation`이다.

고려대 KURE 계열을 추가 조사했고, 사용자 지시로 공식 KURE-v1 revision을 고정해 같은 조건의 평가를 시작했다. [별도 CPU 추론 환경](../../docker/kure/README.md)에서 공식 출력과의 일치를 먼저 검증했다. KURE-v2·Sparse·Reranker는 검색 방식이 달라 별도 후속 실험으로 구분한다.

`data/questions.json`의 `evaluationReady=true`는 고정 원문·청크·정답으로 **임베딩 검색 평가를 실행할 준비가 됐다**는 뜻이다. 답변 생성, 법률 자문 정확도, 서비스 배포 준비를 뜻하지 않는다.

2026-10-15 EmbeddingGemma 768차원의 전체 벡터화·질문 실행을 완료했다. [실제 법령 기준선 결과](reports/embeddinggemma/README.md)와 [모델·입력 해시 및 지표 JSON](reports/embeddinggemma/summary.json)을 보존했다.

## 고정 범위

- 주 법령 20개 + 보충 시행령·규칙 등 13개
- 단순노무직종 고시 1건, 필요한 PDF 별표 2건, 최저임금법 적용 특례 부칙
- 본문·추가 근거 5,486개 단위 / 청크 6,634개
- 정답 있는 질문 100개 + 범위 밖 질문 10개
- 개발용: 양성 30개 / 음성 5개, held-out: 양성 70개 / 음성 5개

법령 20개는 수동 선정했다. 직접 작성한 후보 120개에서 법령별 5개를 시드 `20261015`로 무작위 선정하고 공식 원문 검토 후 8개 질문을 구체화했다. 실제 사용자 질문이나 전체 법령에서 무작위 추출한 표본은 아니다. 범위 밖 질문은 별도로 작성했다.

같은 정답 조문·별표가 양쪽 분할에 동시에 포함되지 않도록 연결된 질문을 묶어 분할을 보정했다. 최초 선정 정보와 `originalSplit`은 보존했다. held-out 결과로 반복 조정하지 않는다.

코퍼스는 33개 법령의 본문과 평가에 필요한 부칙·별표·고시다. 삭제 본문 조문, 그 밖의 과거 부칙·별표·서식, 판례는 제외했다. 범위 밖 판정도 이 고정 범위 기준이다.

## 실행

JDK 17, Windows PowerShell 5.1, CPU Ollama 컨테이너를 사용한다. 모델 입력 계약과 차원은 `review/models.json`에서 관리한다. Spring 서버·MySQL·Qdrant·OpenAI 키는 평가에 필요하지 않다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/validate-evaluation.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-evaluator.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/evaluate.ps1
```

기본 주소는 `http://127.0.0.1:11435`이며 `-Port`로 바꾼다. 모델 다운로드는 자동 수행하지 않는다. CPU 6 threads / GPU 0 / 순차 요청 / 기본 배치 16개 / `truncate=false`다. 완료한 벡터 배치는 중간 실패 뒤 재사용한다. 다른 모델이 로드돼 있으면 거부하고, 완료·실패 시 평가 모델을 언로드한다.

EmbeddingGemma는 공식 검색 접두문을 적용하고, BGE-M3는 질문에 instruction을 붙이지 않는다. 두 모델 모두 동일한 법령명·조문명과 본문을 사용한다. 벡터 수·모델별 차원·유한값·0이 아닌 벡터를 검증하며 컨텍스트 초과는 잘라 쓰지 않고 실패한다. [BGE-M3 공식 입력 계약](https://huggingface.co/BAAI/bge-m3)을 기준으로 dense 검색만 비교한다.

## 청킹과 지표

`char500-overlap64-article-v1`: 조문 경계를 넘지 않는 최대 500문자 / 중첩 64문자 청킹이다. 512토큰 청킹과 다르다. 공백을 정규화하고 숫자·부정·조항 내용은 보존한다. 모델별 비교에는 같은 청크를 사용한다. 청킹 변경은 새 코퍼스 버전으로 관리한다.

정답은 조문·별표·고시 단위다. 긴 조문의 청크 여러 개를 별개 정답으로 세지 않는다. 자식 청크 중 최대 코사인 유사도로 단위 순위를 정하고, 양성 질문에 Recall@5/10·MRR·1위 적중률을 계산한다. `relevantChunkGroups`는 그 단위의 실제 청크 목록이며, 개별 청크의 모든 문장이 정답이라는 세밀한 라벨은 아니다.

평가기는 전체 벡터의 코사인 유사도를 계산하는 정확 전수 검색이다. Qdrant의 근사 검색 설정이나 애플리케이션의 검색·답변 흐름을 통합 검증한 것은 아니다.

음성은 Recall/MRR에서 제외한다. 개발용 양성 30개·음성 5개의 1위 점수로 답변 가능 여부 분류의 balanced accuracy가 최대인 임계값을 선택한다. 동률이면 더 높은 임계값을 고른다. 이를 고정해 held-out 음성 수용률을 계산한다. 검색 점수 기반 분류이며 AI의 실제 답변·근거 채택을 검증한 것은 아니다. 음성이 각 5개이므로 비율은 참고치다.

## 파일과 결과

- `data/questions.json`: 질문·공식 조문·실제 청크 ID·분할·검토 내역
- `data/corpus.jsonl`, `data/corpus-manifest.json`: 고정 청크와 33개 법령 버전·시행일·해시·청킹 계약
- `verified-evidence.json`, `special-evidence.json`: 검토 근거 원문
- `question-bank.json`: 최초 선정 후보 120개
- `review-decisions.json`, `article-review-map.json`: 1차 검토 기록
- `supplemental-review.json`, `special-sources.json`: 보충 근거와 공식 출처
- `reports/verification.md`: 검증 결과

`artifacts/results/embeddinggemma-<실행시각>/`은 Git에서 제외한다.

- `summary.csv`: 분할별 검색 지표·음성 수용률·질문 지연
- `queries.csv`: 질문별 첫 정답 순위·유사도·지표·지연
- `top10.csv`: 질문별 상위 10개 조문·대표 청크·유사도·정답 여부
- `report.json`: 모델 digest·차원·질문/코퍼스 해시·임계값 선정 방식·실행 정보

`artifacts/results/shared-cache/`에 모델 digest·차원·입력 형식별 binary 벡터 배치를 저장한다. 벡터의 키는 실제 입력 문자열의 SHA256이며 청크 크기가 달라도 입력이 동일하면 재사용한다. 기존 Gemma 기준선 캐시도 입력을 바꾸지 않고 이관하며 지표 재현을 확인했다. 정규화한 float32 벡터를 사용하고 다른 모델·코퍼스와 혼합하지 않는다. 질문 지연은 준비 후 각 질문 한 번의 HTTP 시간이며 반복 성능 벤치마크는 아니다. 색인 시간은 해당 실행에서 새로 만든 벡터 작업 시간으로 기록한다.

## 원문과 재생성

원문 HTML·PDF는 Git에서 제외된 `artifacts/snapshots/`에 보존한다. 고정 코퍼스와 검토 근거는 Git에 남아 평가 실행에는 원문 재다운로드가 필요하지 않다. 완료 상태를 덮어쓰는 재선정·1차 검토 재적용은 차단했다.

새 데이터셋 버전에서 `fetch-statutes.ps1`로 현재 연결되는 원문을 수집하고 `fetch-special-sources.ps1`로 고시·별표를 수집한다. 기존 평가 재현용으로 최신 원문을 다시 받지 않는다. 기존 스냅샷 덮어쓰기는 명시적 `-Refresh`가 필요하다. 보충 법령 수집 인수는 다음과 같다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/fetch-statutes.ps1 -DatasetPath evaluation/legal-retrieval/review/supplemental-sources.json -OutputDirectory evaluation/legal-retrieval/artifacts/snapshots/supplemental
```

저장된 원문과 검토 결정으로 재생성하려면 `prepare-evaluation.ps1` 실행 후 `validate-evaluation.ps1`로 검사한다. 원문·검토 내용을 갱신하면 새 데이터셋 버전으로 관리한다.

## HTML·PDF 추출

본문·고시는 공식 HTML에서, 필요한 PDF 별표는 Apache PDFBox 3.0.5로 추출했다. 고시 본문과 별표의 적용 조항 목록·인가 기준을 대조했다. 원문 형식·추출기 버전·해시를 기록했다. 모델 비교에서는 추출한 텍스트를 고정한다. 동일 원문의 HTML/PDF 추출 정확도를 비교하는 별도 실험을 수행한 것은 아니다.

## 폴더 안내

| 폴더 | 내용 |
| --- | --- |
| `data/` | 평가에 사용하는 고정 질문·청크·코퍼스 manifest |
| `scripts/` | 수집·준비·검증·실행 도구, `lib/`에는 공통 경로와 계산 코드 |
| `review/` | 후보 질문·검토 결정·공식 근거·보충 자료 매핑 |
| `reports/` | 검증 보고서와 모델별 확정 결과 |
| `artifacts/` | 원문 다운로드·벡터 캐시·상세 실행 결과. Git 제외 |

최초 가상 샘플 측정은 `review/archive/`에 보관한다. 현재 모델 비교에는 `data/`의 실제 법령 평가셋을 사용한다.
## 모델·청크 크기 실험

동일한 공식 원문 5,486개 단위·질문 110개·정답 조문·분할을 고정한다. 조문 경계를 유지하면서 최대 문자 수만 300 / 500 / 700으로 바꾸고 중첩은 64문자로 고정한다. 300문자는 8,497개, 500문자는 6,634개, 700문자는 6,031개 청크다. 각 변형에서 원문 누락·내용 변경이 없는지 공식 원문에 대해 확인했다.

```powershell
# 500문자 기준선에서 원문을 복원해 변형 생성: 원문 재다운로드 불필요
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/prepare-variants.ps1
# 모델 하나를 500 -> 300 -> 700문자 순서로 평가하고 reports에 검증 결과 저장
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-model.ps1 -Model bge-m3
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/run-model.ps1 -Model embeddinggemma
# 완료된 결과만 비교표로 집계
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/compare-results.ps1
```

한 구성만 실행하려면 `scripts/evaluate.ps1 -Model bge-m3 -DatasetDirectory evaluation/legal-retrieval/data/variants/char300-overlap64-article-v1`처럼 지정한다. `-DatasetDirectory`를 생략하면 500문자 기준선이다.

변형 데이터는 `data/variants/<청킹 버전>/`, 모델별 확정 결과는 `reports/<모델>/<청킹 버전>/`에 둔다. 각 결과 폴더에는 요약 JSON·요약 CSV·질문별 지표 CSV·상위 조문 유사도 CSV를 함께 저장한다. [모델·청크 비교표](reports/comparison.md)와 [비교 CSV](reports/comparison.csv)는 실행이 완료된 구성만 포함한다.

이 실험은 문자 수만 바꾸는 비교다. 토크나이저 기반 청킹이나 중첩 크기의 독립 비교는 포함하지 않는다. 청크 설정 선택은 개발용 지표로 하고, held-out 점수를 반복해서 최적화하지 않는다. `run-model.ps1`은 커밋·푸시하지 않으며 공유 Git 반영은 검증 뒤 별도로 수행한다.
