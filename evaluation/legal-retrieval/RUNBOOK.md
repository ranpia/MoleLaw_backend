# 실험 실행 안내

[실험 소개](README.md) · [실험 계획](PLAN.md) · [결과 안내](experiments/README.md)

최초 모델 비교와 후속 Qdrant 실험은 실행 도구가 다르다. 아래 최초 모델 비교 명령은 기존 입력 재현용이다. 개별 실험의 고정 입력·실행 조건은 해당 실험 문서를 우선한다. 원문이나 평가셋을 다시 생성하려면 기존 버전을 보존하고 새 버전에서 작업한다.

## 실행

최초 로컬 모델 비교는 JDK 17·Windows PowerShell 5.1·모델 추론 컨테이너를 사용하며 Spring 서버·MySQL·OpenAI 키 없이 실행할 수 있다. Qdrant 기반 후속 실험은 Qdrant도 필요하다. 모델 입력 계약은 `experiments/model-comparison/review/models.json`에 있다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/validate-evaluation.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-evaluator.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/evaluate.ps1
```

기본 주소는 `http://127.0.0.1:11435`이며 `-Port`로 바꾼다. 모델 다운로드는 자동 수행하지 않는다. CPU 6 threads / GPU 0 / 순차 요청 / 기본 배치 16개 / `truncate=false`다. 완료한 벡터 배치는 중간 실패 뒤 재사용한다. 다른 모델이 로드돼 있으면 거부하고, 완료·실패 시 평가 모델을 언로드한다.

EmbeddingGemma는 공식 검색 접두문을 적용하고, BGE-M3는 질문에 instruction을 붙이지 않는다. 두 모델 모두 동일한 법령명·조문명과 본문을 사용한다. 벡터 수·모델별 차원·유한값·0이 아닌 벡터를 검증하며 컨텍스트 초과는 잘라 쓰지 않고 실패한다. [BGE-M3 공식 입력 계약](https://huggingface.co/BAAI/bge-m3)을 기준으로 dense 검색만 비교한다.

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
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/fetch-statutes.ps1 -DatasetPath evaluation/legal-retrieval/experiments/model-comparison/review/supplemental-sources.json -OutputDirectory evaluation/legal-retrieval/artifacts/snapshots/supplemental
```

저장된 원문과 검토 결정으로 재생성하려면 `prepare-evaluation.ps1` 실행 후 `validate-evaluation.ps1`로 검사한다. 원문·검토 내용을 갱신하면 새 데이터셋 버전으로 관리한다.

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

한 구성만 실행하려면 `scripts/evaluate.ps1 -Model bge-m3 -DatasetDirectory evaluation/legal-retrieval/experiments/model-comparison/data/variants/char300-overlap64-article-v1`처럼 지정한다. `-DatasetDirectory`를 생략하면 500문자 기준선이다.

변형 데이터는 `experiments/model-comparison/data/variants/<청킹 버전>/`, 모델별 확정 결과는 `experiments/model-comparison/reports/<모델>/<청킹 버전>/`에 둔다. 각 결과 폴더에는 요약 JSON·요약 CSV·질문별 지표 CSV·상위 조문 유사도 CSV를 함께 저장한다. [모델·청크 비교표](experiments/model-comparison/reports/comparison.md)와 [비교 CSV](experiments/model-comparison/reports/comparison.csv)는 실행이 완료된 구성만 포함한다.

이 실험은 문자 수만 바꾸는 비교다. 토크나이저 기반 청킹이나 중첩 크기의 독립 비교는 포함하지 않는다. 청크 설정 선택은 개발용 지표로 하고, held-out 점수를 반복해서 최적화하지 않는다. `run-model.ps1`은 커밋·푸시하지 않으며 공유 Git 반영은 검증 뒤 별도로 수행한다.

## Qwen3 및 Qdrant 자료 관리

Qwen3-Embedding-0.6B 공식 revision과 1024차원을 고정한다. 청크별 완료 수치는 [Qwen3 결과](experiments/model-comparison/reports/qwen3-0.6b/README.md)와 통합 비교표를 기준으로 확인한다. [실행 환경](../../docker/qwen3/README.md)에 입력 지시문과 자원 제한을 기록했다. 질문 정제본과 정답·분할은 기존과 동일하다.

실험 데이터는 Qdrant에서 관리한다. 법령 청크는 모델·청크별 컬렉션에, 질문 벡터·원본/정제 텍스트·정답과 세 청크의 Qdrant 전수 검색 결과는 모델별 별도 컬렉션에 저장한다. `store-qdrant-catalog.ps1`는 고정 데이터셋·정제본·모델 설정과 질문 JSON의 원본 바이트를 카탈로그에 보존한다. 다음 명령은 기존 모델의 질문과 검색 결과도 멱등 upsert하며 다른 소유자·체크포인트·데이터셋의 컬렉션에는 쓰지 않는다. Git의 고정 입력·결과는 재현과 공유용으로 유지한다.

```powershell
.\evaluation\legal-retrieval\scripts\store-qdrant-evaluation.ps1 -Model bge-m3
```

## 모델 실험 실행 원칙

사용자 요청: 앞으로 모델 다운로드·추론·평가는 별도 터미널에서 실행하고 로그를 파일에 남긴다. 채팅 에이전트가 반복 폴링하거나 분 단위 진행 메시지를 생성하지 않는다. 사용자가 상태·결과를 요청할 때 확인해 토큰 사용을 줄인다. Qwen 단일 청크 실행기는 `scripts/run-qwen-terminal.ps1`이며 `-Size`로 청크를 지정한다. BGE·Qwen Recall@50 순차 실행기는 `scripts/run-recall50-terminal.ps1`이다. 요청한 청크 평가 후 컨테이너를 종료한다. 종료된 로그와 새 실행 로그는 Git 제외된 artifacts/logs에서 관리한다. 실행 중인 기존 로그는 기존 위치를 유지한다.

