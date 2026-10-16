# Additional ranking metrics

Positive questions only; macro averages over frozen gold source units. Binary relevance, log2(rank+1) discount; IDCG uses min(k, total gold units), including gold outside retrieved top ten.

| Model | Chunk | Split | N | Hit@5 | Complete@5 | Precision@5 | nDCG@5 | nDCG@10 |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| bge-m3:latest | char300-overlap64-article-v1 | development | 30 | 0.7 | 0.4333 | 0.1867 | 0.4725 | 0.5238 |
| bge-m3:latest | char300-overlap64-article-v1 | held_out | 70 | 0.8571 | 0.6 | 0.2086 | 0.6275 | 0.6764 |
| bge-m3:latest | char500-overlap64-article-v1 | development | 30 | 0.7667 | 0.5 | 0.2 | 0.5377 | 0.5863 |
| bge-m3:latest | char500-overlap64-article-v1 | held_out | 70 | 0.8714 | 0.6143 | 0.2229 | 0.6759 | 0.7105 |
| bge-m3:latest | char700-overlap64-article-v1 | development | 30 | 0.8 | 0.5 | 0.2133 | 0.5469 | 0.5791 |
| bge-m3:latest | char700-overlap64-article-v1 | held_out | 70 | 0.8571 | 0.6 | 0.2171 | 0.6635 | 0.7054 |
| embeddinggemma:latest | char300-overlap64-article-v1 | development | 30 | 0.7333 | 0.4667 | 0.2 | 0.5301 | 0.5675 |
| embeddinggemma:latest | char300-overlap64-article-v1 | held_out | 70 | 0.8714 | 0.6143 | 0.2229 | 0.6849 | 0.7217 |
| embeddinggemma:latest | char500-overlap64-article-v1 | development | 30 | 0.7333 | 0.5 | 0.2 | 0.5357 | 0.5719 |
| embeddinggemma:latest | char500-overlap64-article-v1 | held_out | 70 | 0.9143 | 0.6714 | 0.24 | 0.7385 | 0.7589 |
| embeddinggemma:latest | char700-overlap64-article-v1 | development | 30 | 0.7667 | 0.5 | 0.2133 | 0.5543 | 0.5821 |
| embeddinggemma:latest | char700-overlap64-article-v1 | held_out | 70 | 0.9 | 0.6571 | 0.2314 | 0.7077 | 0.7319 |
| kure-v1:official | char300-overlap64-article-v1 | development | 30 | 0.6667 | 0.4333 | 0.1867 | 0.5316 | 0.5856 |
| kure-v1:official | char300-overlap64-article-v1 | held_out | 70 | 0.8429 | 0.5714 | 0.2086 | 0.6139 | 0.6583 |
| kure-v1:official | char500-overlap64-article-v1 | development | 30 | 0.7333 | 0.4333 | 0.1867 | 0.537 | 0.5987 |
| kure-v1:official | char500-overlap64-article-v1 | held_out | 70 | 0.8571 | 0.6143 | 0.2143 | 0.6476 | 0.6846 |
| kure-v1:official | char700-overlap64-article-v1 | development | 30 | 0.7333 | 0.4333 | 0.1933 | 0.5438 | 0.6021 |
| kure-v1:official | char700-overlap64-article-v1 | held_out | 70 | 0.8714 | 0.6429 | 0.2286 | 0.637 | 0.6558 |

These are post-hoc supporting metrics, not a replacement for the predeclared development Recall@5 selection rule. Unlabelled units count as non-gold, not as independently proven irrelevant.

Definitions, similarity examples and deferred metrics: [evaluation guide](similarity-and-metrics.md).
