# 법령 검색 평가 준비 완료

기준일: 2026-10-15. evaluationReady=true. 고정된 공식 원문 코퍼스의 dense 검색 평가 준비를 완료했다. 개별 사건의 답변 생성·법률 자문 정확도를 인증하는 상태는 아니다.

## 이전에 준비가 끝나지 않았던 이유

법령 20개 다운로드가 실패했던 것이 아니다. 17개 질문은 법률 본문이 시행령·시행규칙·고시·별표 등에 세부 조건을 위임하고 있었고, 정답 조문을 실제 색인 청크로 연결하지 않은 상태였다. 추가 근거 수집·대조, 청킹, 정답 연결을 완료했다.

## 완료한 준비

- 주 법령 20개 + 보충 법령 13개: 버전·시행일·공식 원문 SHA256 확인
- 단순노무직종 고시·근로기준법 시행령 별표 1·최저임금법 시행규칙 별표 3 대조
- 최저임금법 법률 제15666호 부칙 적용 특례를 정답 근거에 포함
- 5,486개 검색 단위 / 6,634개 청크 생성 및 내용 해시 고정
- 정답 있음 100개 / 범위 밖 10개 질문과 실제 청크 ID 연결
- 같은 정답 단위가 개발용·held-out에 동시에 존재하지 않도록 분할 보정
- 개발용 35개(양성30/음성5), held-out 75개(양성70/음성5)
- 유사도 1/0/-1 기준값·정규화·캐시 왕복·조문별 최대 점수 집계 검증

## 고정한 법령 버전

| 구분 | 법령 | 공식 ID | MST | 시행일 |
| --- | --- | --- | --- | --- |
| 주 법령 | [민법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=284415&chrClsCd=010202&efYd=20260317) | 001706 | 284415 | 2026-03-17 |
| 주 법령 | [주택임대차보호법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=276291&chrClsCd=010202&efYd=20260102) | 001248 | 276291 | 2026-01-02 |
| 주 법령 | [상가건물 임대차보호법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=279651&chrClsCd=010202&efYd=20260512) | 009276 | 279651 | 2026-05-12 |
| 주 법령 | [근로기준법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=285279&chrClsCd=010202&efYd=20261008) | 001872 | 285279 | 2026-10-08 |
| 주 법령 | [최저임금법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=218303&chrClsCd=010202&efYd=20200526) | 000129 | 218303 | 2020-05-26 |
| 주 법령 | [근로자퇴직급여 보장법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=284455&chrClsCd=010202&efYd=20260918) | 009883 | 284455 | 2026-09-18 |
| 주 법령 | [남녀고용평등과 일ㆍ가정 양립 지원에 관한 법률](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=284457&chrClsCd=010202&efYd=20260918) | 000130 | 284457 | 2026-09-18 |
| 주 법령 | [전자상거래 등에서의 소비자보호에 관한 법률](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=282793&chrClsCd=010202&efYd=20260721) | 009318 | 282793 | 2026-07-21 |
| 주 법령 | [방문판매 등에 관한 법률](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290239&chrClsCd=010202&efYd=20261002) | 000354 | 290239 | 2026-10-02 |
| 주 법령 | [약관의 규제에 관한 법률](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=260021&chrClsCd=010202&efYd=20240807) | 000667 | 260021 | 2024-08-07 |
| 주 법령 | [소비자기본법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=283837&chrClsCd=010202&efYd=20260911) | 001589 | 283837 | 2026-09-11 |
| 주 법령 | [개인정보 보호법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=283839&chrClsCd=010202&efYd=20260911) | 011357 | 283839 | 2026-09-11 |
| 주 법령 | [형법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290185&chrClsCd=010202&efYd=20261002) | 001692 | 290185 | 2026-10-02 |
| 주 법령 | [형사소송법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290189&chrClsCd=010202&efYd=20261002) | 001671 | 290189 | 2026-10-02 |
| 주 법령 | [민사소송법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290113&chrClsCd=010202&efYd=20261002) | 001700 | 290113 | 2026-10-02 |
| 주 법령 | [민사집행법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=268837&chrClsCd=010202&efYd=20260201) | 009290 | 268837 | 2026-02-01 |
| 주 법령 | [소액사건심판법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=249281&chrClsCd=010202&efYd=20230328) | 001219 | 249281 | 2023-03-28 |
| 주 법령 | [채무자 회생 및 파산에 관한 법률](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290631&chrClsCd=010202&efYd=20261002) | 009930 | 290631 | 2026-10-02 |
| 주 법령 | [도로교통법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=291133&chrClsCd=010202&efYd=20261013) | 001638 | 291133 | 2026-10-13 |
| 주 법령 | [자동차손해배상 보장법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290659&chrClsCd=010202&efYd=20261002) | 001746 | 290659 | 2026-10-02 |
| 보충 | [상가건물 임대차보호법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=287139&chrClsCd=010202&efYd=20260701) | 009361 | 287139 | 2026-07-01 |
| 보충 | [근로기준법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=270551&chrClsCd=010202&efYd=20251023) | 003058 | 270551 | 2025-10-23 |
| 보충 | [최저임금법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=206564&chrClsCd=010202&efYd=20190101) | 005247 | 206564 | 2019-01-01 |
| 보충 | [최저임금법 시행규칙](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=282351&chrClsCd=010202&efYd=20260102) | 008480 | 282351 | 2026-01-02 |
| 보충 | [근로자퇴직급여 보장법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=284693&chrClsCd=010202&efYd=20260324) | 010035 | 284693 | 2026-03-24 |
| 보충 | [남녀고용평등과 일ㆍ가정 양립 지원에 관한 법률 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=288719&chrClsCd=010202&efYd=20260918) | 003140 | 288719 | 2026-09-18 |
| 보충 | [전자상거래 등에서의 소비자보호에 관한 법률 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=288143&chrClsCd=010202&efYd=20260721) | 009338 | 288143 | 2026-07-21 |
| 보충 | [방문판매 등에 관한 법률 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=286997&chrClsCd=010202&efYd=20260701) | 003572 | 286997 | 2026-07-01 |
| 보충 | [소비자기본법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=289451&chrClsCd=010202&efYd=20260911) | 003979 | 289451 | 2026-09-11 |
| 보충 | [개인정보 보호법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=289537&chrClsCd=010202&efYd=20260911) | 011468 | 289537 | 2026-09-11 |
| 보충 | [공소청법](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=290007&chrClsCd=010202&efYd=20261002) | 015092 | 290007 | 2026-10-02 |
| 보충 | [민사집행법 시행령](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=283025&chrClsCd=010202&efYd=20260201) | 010014 | 283025 | 2026-02-01 |
| 보충 | [채무자 회생 및 파산에 관한 규칙](https://www.law.go.kr/LSW/lsInfoR.do?lsiSeq=280737&chrClsCd=010202&efYd=20260301) | 010185 | 280737 | 2026-03-01 |

## 보충 근거 검증 결과

| 질문 ID | 확인한 자료 |
| --- | --- |
| commercial-4 | 상가건물 임대차보호법 시행령 |
| commercial-6 | 상가건물 임대차보호법 시행령 |
| labor-5 | 근로기준법 시행령, 별표 1 상시 4명 이하의 근로자를 사용하는 사업 또는 사업장에 적용하는 법 규정 |
| minimum-3 | 최저임금법 시행령, 최저임금법 제5조에 따른 단순노무직종 근로자 지정 고시 |
| minimum-2 | 최저임금법 시행규칙 |
| minimum-5 | 최저임금법 시행령 |
| minimum-6 | 최저임금법 시행령, 최저임금법 시행규칙, 별표 3 최저임금 적용 제외의 인가 기준 |
| retirement-3 | 근로자퇴직급여 보장법 시행령 |
| employment-4 | 남녀고용평등과 일ㆍ가정 양립 지원에 관한 법률 시행령 |
| employment-1 | 남녀고용평등과 일ㆍ가정 양립 지원에 관한 법률 시행령 |
| ecommerce-4 | 전자상거래 등에서의 소비자보호에 관한 법률 시행령 |
| door-5 | 방문판매 등에 관한 법률 시행령 |
| consumer-3 | 소비자기본법 시행령 |
| privacy-5 | 개인정보 보호법 시행령 |
| criminalprocedure-5 | 공소청법 |
| execution-5 | 민사집행법 시행령 |
| rehabilitation-4 | 채무자 회생 및 파산에 관한 규칙 |

## 평가 해석

청크는 최대500문자·중첩64문자이며 조문 경계를 넘지 않는다. 512토큰 청킹을 구현했다고 표시하지 않는다. 정답은 조문/별표 단위로 묶으며 자식 청크 중 최대 유사도로 순위를 정한다. 양성 질문에만 Recall@5/10·MRR·1위 적중률을 계산한다.

음성 질문은 고정 코퍼스 범위 밖의 주제다. 개발용 점수로 임계값을 고정하고 held-out에서 음성 수용률을 별도로 계산한다. 이는 AI가 실제 답변을 잘 거절하는지를 평가한 결과가 아니다.

코퍼스는 본문과 필요한 부칙·별표·고시에 한정된다. 다른 과거 부칙·별표·판례까지 수집한 완전한 법률 데이터베이스는 아니다. 해당 범위와 질문의 검색 평가 준비를 완료한 것이다.

## 검증

validate-evaluation.ps1: 코퍼스/청크 해시, 청크 길이·오프셋, 110개 질문의 정답 참조, 30/70 양성 및 5/5 음성 분할, 공유 정답 단위 누수 없음 확인.

test-evaluator.ps1: 비정규화 벡터의 cosine 기준 1/0/-1, binary 캐시 읽기/쓰기, 조문별 대표 청크 집계, 잘못된 벡터 거부 확인.

실행 중인 Spring 서버의 법령 조회는 401로 인증 요구를 확인했다. 평가에는 공식 원문과 별도 Ollama API를 사용하며 애플리케이션 DB·Qdrant·보안 정책은 변경하지 않았다.

실제 모델 실행 결과는 ../artifacts/results/embeddinggemma-<시각>/의 report.json·summary.csv·queries.csv·top10.csv에 별도로 저장한다.

## 실제 실행 확인

EmbeddingGemma 768차원으로 6,634개 벡터와 110개 질문 평가를 완료했다. held-out 1위 적중률 70%, Recall@5 79.93%, Recall@10 84.60%, MRR 0.7861이다. 범위 밖 held-out 질문은 5개 중 3개가 개발용에서 고른 임계값 이상이므로 점수 임계값의 한계가 확인됐다. 상세는 [기준선 보고서](embeddinggemma/README.md)를 참고한다.
