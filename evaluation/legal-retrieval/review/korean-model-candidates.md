# 한국어 추가 학습 임베딩 모델 조사

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
