# Model and chunk comparison

Character-based chunks with 64-character overlap; identical source units, questions and splits.

| Model | Dimensions | Characters | Dev Recall@5 | Dev MRR | Held-out top-1 | Held-out Recall@5 | Held-out MRR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| bge-m3:latest | 1024 | 500 | 64.06% | 0.5928 | 60.0% | 75.40% | 0.7226 |
| embeddinggemma:latest | 768 | 500 | 62.28% | 0.5854 | 70.0% | 79.93% | 0.7861 |

Use development results to choose chunk/model settings. Held-out rows are descriptive final checks, not a repeated tuning objective.

Recall ranks distinct source articles/annexes by maximum child-chunk score. No-answer rates use only five held-out negative questions and score-based thresholds, not generated answers.

Raw similarity thresholds are fitted independently per configuration; do not compare absolute cosine scores across models.

Query latency is one warm request per question, not a repeated load test. Character limits are not tokenizer-exact token limits.
