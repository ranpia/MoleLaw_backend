import unittest
from unittest.mock import patch
import reranker


class Encoder:
    def tokenizer(self, queries, texts, **kwargs):
        return {"length": [5] * len(texts)}

    def predict(self, pairs, **kwargs):
        return [float(i) for i, _ in enumerate(pairs)]


class RerankerTest(unittest.TestCase):
    def payload(self):
        return {"model": reranker.MODEL_ID, "revision": reranker.REVISION, "query": "question",
                "documents": [{"id": "123:1", "text": "first"}, {"id": "123:2", "text": "second"}]}

    def test_returns_all_ids_and_finite_scores(self):
        with patch.object(reranker, "ENABLED", True), patch.object(reranker, "model", Encoder()):
            result = reranker.rerank(self.payload())
            self.assertEqual(result["results"], [{"id": "123:1", "score": 0.0}, {"id": "123:2", "score": 1.0}])

    def test_rejects_duplicate_ids_and_wrong_revision(self):
        with patch.object(reranker, "ENABLED", True), patch.object(reranker, "load_model") as load:
            p = self.payload()
            p["documents"][1]["id"] = "123:1"
            with self.assertRaises(ValueError): reranker.rerank(p)
            p = self.payload()
            p["revision"] = "other"
            with self.assertRaises(ValueError): reranker.rerank(p)
            load.assert_not_called()

    def test_rejects_oversized_pair_before_predict_instead_of_truncating(self):
        encoder = Encoder()
        with patch.object(reranker, "ENABLED", True), patch.object(reranker, "model", encoder), \
                patch.object(encoder, "tokenizer", return_value={"length": [reranker.MAX_TOKENS + 1, 5]}), \
                patch.object(encoder, "predict") as predict:
            with self.assertRaisesRegex(ValueError, "truncation is disabled"):
                reranker.rerank(self.payload())
            predict.assert_not_called()
