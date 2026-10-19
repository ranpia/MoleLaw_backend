# Qdrant 실험용 자료 관리

로컬 Qdrant를 이용해 기존 법령 벡터를 보관하고 검색하는 실험 환경이다. 별도 Qdrant 컨테이너를 늘리지 않고 이미 실행 중인 인스턴스에 실험용 컬렉션을 만든다. 서비스의 Spring 설정·기본 컬렉션은 변경하지 않는다. 새 컨테이너 이미지나 임베딩 모델 다운로드는 필요하지 않다.

## 들어 있는 자료

| 모델 | 차원 | 청크 | 저장 위치 |
| --- | ---: | ---: | --- |
| EmbeddingGemma | 768 | 700문자 / 중복 64 | `molelaw_exp_embeddinggemma_c700_66106d14_85462619` |
| BGE-M3 | 1024 | 700문자 / 중복 64 | `molelaw_exp_bge_m3_c700_66106d14_79076464` |

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
```

컬렉션 이름의 끝은 코퍼스·모델 해시다. 현재 스크립트는 고정된 700문자 평가 데이터만 받는다. 다른 법령 버전이나 청크 실험으로 확장할 때는 별도 컬렉션을 만들고 입력 검증도 함께 갱신해야 한다. 다른 내용의 자료를 기존 컬렉션에 섞지 않는다.

## 질문으로 검색하기

질문 검색에는 기존 Ollama 실험 컨테이너가 필요하다. 처음 한 번 `docker start molelaw-embedding-lab-ollama-1`로 켠다. Spring 서버나 OpenAI 호출은 필요 없다. 기본 Ollama 포트는 11435, Qdrant HTTP 포트는 6333이다.

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
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/test-qdrant-lab.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Verify -Model embeddinggemma
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Verify -Model bge-m3

# 해당 모델의 현재 실험 컬렉션만 삭제할 때 실행한다
powershell -NoProfile -ExecutionPolicy Bypass -File evaluation/legal-retrieval/scripts/qdrant-lab.ps1 -Action Delete -Model embeddinggemma
```

색인·검색·삭제 전에 컬렉션의 실험 소유자, 모델 해시, 코퍼스 해시, 차원, 거리 함수를 검사한다. 소유자가 다른 컬렉션은 변경하지 않는다. 적재 도중 중단된 컬렉션은 자료 수가 맞지 않으면 검색을 거부하며 `Index` 재실행으로 복구할 수 있다. 인증 키는 환경변수 `QDRANT_API_KEY`, 없으면 `docker/.env.compose`, 그다음 루트 `.env`에서 읽는다. 접속 호스트는 로컬로 한정한다.

`Verify`는 문서 벡터 3개를 사용해 저장 벡터·원문의 왕복 확인, 기존 전수 코사인 계산과 Qdrant의 상위 10개 점수 비교, 조문 묶음과 법령 필터를 검사한다. 동점이면 같은 점수의 조문 순서가 다를 수 있어 점수 일치와 근사 검색 조문 겹침을 별도로 기록한다. 모델별 `*-verification.json`에 검증 기록을 보존한다. 질문 전체의 검색 품질이나 반복 지연시간을 측정한 벤치마크는 아니다.

2026-10-19: Gemma 컬렉션 6,031개 적재와 문서 벡터 검증 완료. BGE-M3도 같은 방식으로 적재·검증한다. 기존 Qdrant에는 컬렉션이 없었으며 이번 작업에서는 실험 컬렉션만 생성했다. 전체 평가 지표는 [원본 질문 비교](../comparison.md), [정제 질문 비교](../query-rewrite-v1/comparison.md)를 참고한다.
