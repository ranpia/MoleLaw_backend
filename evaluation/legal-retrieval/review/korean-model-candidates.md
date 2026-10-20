# 한국어 추가 학습 임베딩 모델 조사

최종 상태 (2026-10-20 정리): KURE-v1 공식 모델의 세 청크 평가와 확대 최종 검증을 완료하고 1024차원·300자·중복 64자·질문 정제 구성을 선정했다. [최종 결과](../reports/final-summary.md)를 참고한다. 아래는 후보 조사 당시 기록이다.

2026-10-15 Hugging Face 모델 카드 조사 기록. 모델 다운로드·실행 및 추가 평가는 하지 않았다. 후보는 확정 전이며 아래 내용은 게시자가 제공한 설명에 근거한다.

| 후보 | 기반 모델 | 출력 차원 | 한국어 추가 학습 정보 |
| --- | --- | ---: | --- |
| [dragonkue/BGE-m3-ko](https://huggingface.co/dragonkue/BGE-m3-ko) | BGE-M3 | 1,024 | 한국어 데이터셋 추가 학습 명시. 카드상 Apache-2.0 |
| [nlpai-lab/KURE-v1](https://huggingface.co/nlpai-lab/KURE-v1) | BGE-M3 | 1,024 | 한국어 query-document-hard-negative 약 200만 예제, CachedGISTEmbedLoss. 카드상 MIT |
| [dragonkue/snowflake-arctic-embed-l-v2.0-ko](https://huggingface.co/dragonkue/snowflake-arctic-embed-l-v2.0-ko) | Snowflake Arctic Embed L v2.0 | 1,024 | AI Hub 행정·뉴스·도서·금융 법률 등 기계독해 데이터. 질문에 전용 query prompt 적용 |
| [nlpai-lab/KoE5](https://huggingface.co/nlpai-lab/KoE5) | multilingual-e5-large | 1,024 | ko-triplet-v1.0, 약 70만 이상 예제. 카드상 MIT. `query:` / `passage:` 역할별 접두사 사용 |

BGE-M3 기반 두 모델은 현재 BGE-M3 기준선과 한국어 추가 학습 효과를 비교할 후보이다. Snowflake 기반 모델은 금융 법률 학습 데이터가 명시되어 있어 별도 기반 모델 비교 후보로 둔다. 공개 벤치마크 점수를 현재 법령 평가 성능으로 간주하지 않는다.

동일한 기반 모델·출력 차원이어도 파인튜닝 후 벡터 공간 호환성을 가정하지 않는다. 후보마다 법령과 질문을 동일 모델로 다시 임베딩하고, 기존 BGE-M3 캐시와 분리한다. Qdrant 연동 시에도 모델별 컬렉션 또는 명명된 벡터로 구분한다. 현재 Qdrant는 변경하지 않았다.

다음 실행 전에 모델 revision, 입력 형식, 토큰 제한, pooling/정규화, 라이선스 및 현재 추론 환경 호환성을 확인한다. 아직 Ollama 실행 가능 여부를 검증하지 않았다.

## 2026-10-16 고려대 KURE 추가 확인

사용자 요청으로 [고려대 공식 KURE 저장소](https://github.com/nlpai-lab/KURE)와 [KURE-v1 모델 카드](https://huggingface.co/nlpai-lab/KURE-v1)를 다시 확인했다. 개발 기관은 고려대 NLP & AI Lab 및 HIAI Institute다.

현재 dense 비교의 다음 우선 후보를 KURE-v1로 둔다. BGE-M3 기반이므로 원본 BGE-M3 대비 한국어 추가 학습 효과를 같은 평가셋에서 살펴볼 수 있다. 학습은 한국어 query-document-hard-negative(5) 약 200만 건, CachedGISTEmbedLoss, batch 4,096, 1 epoch로 설명되어 있다. 공개 벤치마크는 후보를 고르는 참고 자료이며 현재 법령 평가 성적으로 취급하지 않는다.

공식 설정 파일에서 다음을 읽기 전용으로 확인했다.

- [pooling 설정](https://huggingface.co/nlpai-lab/KURE-v1/blob/main/1_Pooling/config.json): CLS pooling, 출력 1,024차원, mean pooling 비활성.
- [모듈 설정](https://huggingface.co/nlpai-lab/KURE-v1/blob/main/modules.json): Transformer → Pooling → Normalize.
- [문장 설정](https://huggingface.co/nlpai-lab/KURE-v1/blob/main/sentence_bert_config.json): 최대 8,192토큰.
- [Sentence Transformers 설정](https://huggingface.co/nlpai-lab/KURE-v1/blob/main/config_sentence_transformers.json): prompts 비어 있음, 기본 prompt 없음, cosine. 공식 사용 예시는 접두사 없는 입력이다.

KURE-v2는 최신 계열이지만 단일 dense 벡터가 아니라 토큰별 128차원 multi-vector와 MaxSim을 쓰는 late-interaction 모델이다. KURE-Sparse는 sparse 검색, KURE-Reranker는 재순위화 모델이므로 각각 후속 별도 실험으로 구분한다. 현재 단일 벡터 평가기에 그대로 끼워 넣지 않는다. 이 구분은 공식 저장소의 모델 유형 및 사용 예시에 근거한다.

추가 조사 후 사용자 실행 지시에 따라 공식 KURE-v1 revision `8b418a58414668e75532ed045c22d9ca018ae2b2`를 고정하고 Sentence Transformers CPU 환경과 모델 다운로드를 준비했다. 한국어 입력 4개에서 공식 모델을 독립적으로 불러온 결과와 평가용 어댑터 출력의 최대 차이는 약 0.00000003으로 허용 오차 0.000001 내였다. 1,024차원·CLS pooling·정규화를 확인한 뒤 동일 법령·질문·청크 조건의 평가를 시작했다. Ollama 변환본은 사용하지 않았다.

[실행 환경 안내](../../../docker/kure/README.md)와 [실제 환경·검증 기록](../reports/kure-v1/environment.json)에 revision·라이브러리 버전·이미지 ID·CPU·검증 결과를 보존한다. PyTorch float32와 기존 Ollama 모델의 BF16/F16 실행 조건 차이를 함께 기록하며, 캐시 재사용량이 다른 색인 시간을 모델 속도로 비교하지 않는다.
