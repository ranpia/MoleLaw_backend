# Model and chunk comparison

Character-based chunks with 64-character overlap; identical source units, questions and splits.

| Model | Dimensions | Characters | Dev Recall@5 | Dev MRR | Held-out top-1 | Held-out Recall@5 | Held-out MRR |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| bge-m3:latest | 1024 | 300 | 57.39% | 0.5196 | 52.86% | 72.79% | 0.6762 |
| bge-m3:latest | 1024 | 500 | 64.06% | 0.5928 | 60.0% | 75.40% | 0.7226 |
| bge-m3:latest | 1024 | 700 | 65.72% | 0.5909 | 58.57% | 73.62% | 0.7179 |
| embeddinggemma:latest | 768 | 300 | 61.28% | 0.5815 | 65.71% | 74.69% | 0.7417 |
| embeddinggemma:latest | 768 | 500 | 62.28% | 0.5854 | 70.0% | 79.93% | 0.7861 |
| embeddinggemma:latest | 768 | 700 | 64.22% | 0.6065 | 62.86% | 78.14% | 0.7422 |
| kure-v1:official | 1024 | 300 | 57.11% | 0.6160 | 51.43% | 71.71% | 0.6592 |
| kure-v1:official | 1024 | 500 | 57.28% | 0.6245 | 55.71% | 73.81% | 0.6935 |
| kure-v1:official | 1024 | 700 | 58.39% | 0.6153 | 48.57% | 76.48% | 0.6526 |

Similarity examples and metric definitions: [guide](similarity-and-metrics.md). Additional Hit@5, Complete@5, Precision@5 and nDCG@5/10: [ranking comparison](ranking-comparison.md).

Use development results to choose chunk/model settings. Held-out rows are descriptive final checks, not a repeated tuning objective.

Primary selection metric: development Recall@5. Supporting metrics: MRR, Recall@10, in-corpus acceptance and out-of-corpus false acceptance; see CSV.

Recall ranks distinct source articles/annexes by maximum child-chunk score. No-answer rates use only five held-out negative questions and score-based thresholds, not generated answers.

Raw similarity thresholds are fitted independently per configuration; do not compare absolute cosine scores across models.

Query latency is one warm request per question, not a repeated load test. Character limits are not tokenizer-exact token limits.

CSV vector size is chunk count x dimension x 4 bytes for float32 values only; excludes IDs, metadata, cache overhead and any ANN index. Cached indexing times are not model throughput measurements.

KURE uses official Sentence Transformers/PyTorch float32; the existing Gemma/BGE runs use Ollama BF16/F16. Runtime and precision differ, so latency is not a pure model comparison. Legacy report JSON remains unchanged; unrecorded dtype is explicitly marked in CSV.
