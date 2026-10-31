# Dense top-50 vs current-service reranker

Same frozen candidates, query text and gold. No embeddings, Qdrant writes, threshold filtering, or tuning.

- Completed full dataset: False
- Reranker: BAAI/bge-reranker-v2-m3 / 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e
- Latency is reranker HTTP time, not end-to-end retrieval latency.

| Condition | N | Hit@5 | Complete@5 | Recall@5 | nDCG@5 | MRR@10 | Recall@50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | 1 | 100.00% | 0.00% | 33.33% | 0.2021 | 0.2500 | 66.67% |
| reranked | 1 | 100.00% | 0.00% | 33.33% | 0.2346 | 0.3333 | 66.67% |

## Paired changes

| Metric | Improved queries | Regressed queries | Unchanged |
| --- | ---: | ---: | ---: |
| hitAt5 | 0 | 0 | 1 |
| completeAt5 | 0 | 0 | 1 |
| recallAt5 | 0 | 0 | 1 |
| ndcgAt5 | 1 | 0 | 0 |

Exploratory results on the previously explored 36 in-corpus questions, not an independent holdout or answer-quality evaluation. Gold coverage may be incomplete. Recall@50 cannot improve when reranking the same 50 candidates. Inspect per-query regressions before changing the service.

[Per-query changes](queries.csv) | [All rankings](rankings.csv) | [Identity and runtime](run.json)
