import importlib.util
import json
import math
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("run_rag_eval", HERE / "run_rag_eval.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(MODULE)


class RagEvalTest(unittest.TestCase):
    def setUp(self):
        self.data = json.loads((HERE / "dataset-v1.json").read_text(encoding="utf-8"))

    def test_dataset_is_valid_and_has_unanswerable_cases(self):
        self.assertEqual([], MODULE.validate_dataset(self.data))
        self.assertTrue(any(not case["answerable"] for case in self.data["cases"]))
        self.assertTrue(any(case["category"] == "multi_hop" for case in self.data["cases"]))

    def test_java_identity_matches_java_hashing_contract(self):
        expected = MODULE.hashlib.sha256(b"8:explicit11:policy-test").hexdigest()
        self.assertEqual(expected, MODULE.document_identity("policy-test"))
        self.assertEqual(expected + ":0", MODULE.chunk_id("policy-test", 0))

    def test_top_k_metrics(self):
        ranked = ["noise", "gold", "support", "other", "last"]
        relevant = {"gold", "support"}
        self.assertEqual(0.0, MODULE.hit_at_k(ranked, relevant, 1))
        self.assertEqual(1.0, MODULE.hit_at_k(ranked, relevant, 2))
        self.assertEqual(0.5, MODULE.recall_at_k(ranked, relevant, 2))
        self.assertEqual(1.0, MODULE.recall_at_k(ranked, relevant, 3))
        self.assertEqual(0.5, MODULE.precision_at_k(ranked, relevant, 2))
        self.assertEqual(0.5, MODULE.reciprocal_rank(ranked, relevant, 5))
        ndcg = MODULE.ndcg_at_k(ranked, {"gold": 2, "support": 1}, 5)
        self.assertAlmostEqual(0.6590, ndcg, places=4)

    def test_retrieval_miss_is_attributed_before_generation(self):
        case = {
            "answerable": True,
            "gold_chunks": [{"document_id": "policy-refund-v1", "chunk": 0}],
        }
        search = {"success": True, "results": [{"id": "wrong"}]}
        self.assertEqual(
            "retrieval_miss",
            MODULE.attribution(
                case, search, {"status": "success", "knowledge_used": False},
                None, accuracy_threshold=0.8, overall_threshold=0.75
            ),
        )

    def test_hit_but_agent_skips_rag_is_attributed_explicitly(self):
        case = {
            "answerable": True,
            "gold_chunks": [{"document_id": "policy-refund-v1", "chunk": 0}],
        }
        gold = MODULE.chunk_id("policy-refund-v1", 0)
        search = {"success": True, "results": [{"id": gold}]}
        self.assertEqual(
            "agent_skipped_retrieval",
            MODULE.attribution(
                case, search,
                {"status": "success", "knowledge_used": False, "verified": True, "grounded": True},
                {"accuracy": 1.0, "overall": 1.0},
                accuracy_threshold=0.8,
                overall_threshold=0.75,
            ),
        )

    def test_agent_failure_stays_in_pass_rate_denominator(self):
        summary = MODULE.group_generation([{
            "category": "refund",
            "chat": {"status": "failed"},
            "judge": None,
            "answer_passed": False,
            "attribution": "agent_failure",
        }])
        self.assertEqual(1, summary["overall"]["cases"])
        self.assertEqual(0.0, summary["overall"]["rag_pipeline_pass_rate"])
        self.assertEqual(0.0, summary["overall"]["answer_pass_rate"])
        self.assertIsNone(summary["overall"]["accuracy"])


if __name__ == "__main__":
    unittest.main()
