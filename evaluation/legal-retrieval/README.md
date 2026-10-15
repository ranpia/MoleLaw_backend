# 법령 임베딩 검색 평가

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

JDK 17, Windows PowerShell 5.1, 기존 CPU Ollama 컨테이너와 설치된 `embeddinggemma:latest`를 사용한다. Spring 서버·MySQL·Qdrant·OpenAI 키는 평가에 필요하지 않다.

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/validate-evaluation.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-evaluator.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/evaluate.ps1
```

기본 주소는 `http://127.0.0.1:11435`이며 `-Port`로 바꾼다. 모델 다운로드는 자동 수행하지 않는다. CPU 6 threads / GPU 0 / 순차 요청 / 기본 배치 16개 / `truncate=false`다. 완료한 벡터 배치는 중간 실패 뒤 재사용한다. 다른 모델이 로드돼 있으면 거부하고, 완료·실패 시 평가 모델을 언로드한다.

공식 EmbeddingGemma 검색 접두문을 적용한다. 질문은 `task: search result | query: ...`, 문서는 `title: 법령명 조문명 | text: ...`다. 벡터 수·768차원·유한값·0이 아닌 벡터를 검증한다. 컨텍스트 초과는 잘라 쓰지 않고 실패한다.

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

`artifacts/results/cache/`에 코퍼스 해시·모델 digest·입력 형식별 binary 벡터 배치를 저장한다. 정규화한 float32 벡터를 사용하고 다른 모델·코퍼스와 혼합하지 않는다. 질문 지연은 준비 후 각 질문 한 번의 HTTP 시간이며 반복 성능 벤치마크는 아니다. 색인 시간은 해당 실행에서 새로 만든 벡터 작업 시간으로 기록한다.

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