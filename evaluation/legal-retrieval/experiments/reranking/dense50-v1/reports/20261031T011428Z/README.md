# Dense top-50 vs current-service reranker

Same frozen candidates, query text and gold. No embeddings, Qdrant writes, threshold filtering, or tuning.

- Completed full dataset: True
- Reranker: BAAI/bge-reranker-v2-m3 / 953dc6f6f85a1b2dbfca4c34a2796e7dde08d41e
- Latency is reranker HTTP time, not end-to-end retrieval latency. Offline HTTP deadline is 300 seconds; production SLA is not evaluated.
- Reranker latency: mean 33,088 ms, p50 30,970 ms, p95 66,123 ms; cached responses 1.

| Condition | N | Hit@5 | Complete@5 | Recall@5 | nDCG@5 | MRR@10 | Recall@50 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| baseline | 36 | 55.56% | 11.11% | 31.39% | 0.2651 | 0.3421 | 56.57% |
| reranked | 36 | 61.11% | 11.11% | 38.19% | 0.3943 | 0.5347 | 56.57% |

## Paired changes

| Metric | Improved queries | Regressed queries | Unchanged |
| --- | ---: | ---: | ---: |
| hitAt5 | 3 | 1 | 32 |
| completeAt5 | 2 | 2 | 32 |
| recallAt5 | 8 | 2 | 26 |
| ndcgAt5 | 16 | 4 | 16 |

Exploratory results on the previously explored 36 in-corpus questions, not an independent holdout or answer-quality evaluation. Gold coverage may be incomplete. Recall@50 cannot improve when reranking the same 50 candidates. Inspect per-query regressions before changing the service.

[Per-query changes](queries.csv) | [All rankings](rankings.csv) | [Identity and runtime](run.json)
