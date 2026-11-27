#!/usr/bin/env python3
"""Run an evidence-backed RAG evaluation against the EchoMind Java HTTP API.

The script intentionally uses only the Python standard library. It can validate
the dataset offline, then seed a dedicated corpus, measure top-k retrieval, ask
the running Agent, score the saved answer with EchoMind's conversation
evaluator, and classify the first observable failure stage.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import statistics
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parent
DEFAULT_DATASET = ROOT / "dataset-v1.json"
DEFAULT_REPORT_DIR = ROOT / "reports"


def java_key(*parts: str | None) -> str:
    """Mirror com.echomind.util.Hashing.key for stable Java chunk IDs."""
    # Java String.length() counts UTF-16 code units, not Python code points.
    encoded = "".join(
        f"{len((part or '').encode('utf-16-le')) // 2}:{part or ''}" for part in parts
    )
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def document_identity(document_id: str) -> str:
    return java_key("explicit", document_id)


def chunk_id(document_id: str, chunk: int) -> str:
    return f"{document_identity(document_id)}:{chunk}"


def sha256_file(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_dataset(path: Path) -> dict[str, Any]:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def validate_dataset(data: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    if data.get("schema_version") != "echomind-rag-eval/v1":
        errors.append("schema_version must be echomind-rag-eval/v1")

    documents = data.get("documents")
    cases = data.get("cases")
    if not isinstance(documents, list) or not documents:
        return errors + ["documents must be a non-empty list"]
    if not isinstance(cases, list) or not cases:
        return errors + ["cases must be a non-empty list"]

    docs: dict[str, dict[str, Any]] = {}
    for index, document in enumerate(documents):
        required = ("document_id", "title", "content")
        missing = [key for key in required if not isinstance(document.get(key), str) or not document[key].strip()]
        if missing:
            errors.append(f"document[{index}] missing {missing}")
            continue
        document_id = document["document_id"]
        if document_id in docs:
            errors.append(f"duplicate document_id: {document_id}")
        docs[document_id] = document
        # Version 1 deliberately uses one stable chunk per document. This makes
        # human labels readable while still resolving to the exact Java chunk ID.
        if len(document["content"]) > 500:
            errors.append(f"{document_id} exceeds the one-chunk 500-codepoint contract")

    case_ids: set[str] = set()
    query_seen: set[str] = set()
    answerable = 0
    unanswerable = 0
    category_counts: Counter[str] = Counter()
    for index, case in enumerate(cases):
        case_id = case.get("id")
        if not isinstance(case_id, str) or not case_id:
            errors.append(f"case[{index}] has no id")
            continue
        if case_id in case_ids:
            errors.append(f"duplicate case id: {case_id}")
        case_ids.add(case_id)
        query = case.get("query")
        if not isinstance(query, str) or not query.strip():
            errors.append(f"{case_id}: empty query")
        elif query in query_seen:
            errors.append(f"{case_id}: duplicate query")
        query_seen.add(query)
        category_counts[str(case.get("category") or "unknown")] += 1
        if not isinstance(case.get("reference_answer"), str) or not case["reference_answer"].strip():
            errors.append(f"{case_id}: empty reference_answer")

        is_answerable = case.get("answerable")
        if not isinstance(is_answerable, bool):
            errors.append(f"{case_id}: answerable must be boolean")
            continue
        if bool(case.get("expected_abstain")) == is_answerable:
            errors.append(f"{case_id}: expected_abstain must be opposite of answerable")

        gold_chunks = case.get("gold_chunks")
        if not isinstance(gold_chunks, list):
            errors.append(f"{case_id}: gold_chunks must be a list")
            continue
        if is_answerable:
            answerable += 1
            if not gold_chunks:
                errors.append(f"{case_id}: answerable case needs gold_chunks")
        else:
            unanswerable += 1
            if gold_chunks:
                errors.append(f"{case_id}: unanswerable case must not have gold_chunks")

        gold_text = []
        seen_chunks: set[str] = set()
        for gold in gold_chunks:
            if not isinstance(gold, dict):
                errors.append(f"{case_id}: invalid gold_chunks item")
                continue
            document_id = gold.get("document_id")
            chunk = gold.get("chunk")
            if document_id not in docs:
                errors.append(f"{case_id}: unknown gold document {document_id}")
                continue
            if type(chunk) is not int or chunk != 0:
                errors.append(f"{case_id}: dataset v1 only accepts chunk index 0")
                continue
            resolved = chunk_id(document_id, chunk)
            if resolved in seen_chunks:
                errors.append(f"{case_id}: duplicate gold chunk {resolved}")
            seen_chunks.add(resolved)
            gold_text.append(docs[document_id]["content"])

        combined = "\n".join(gold_text)
        for fact in case.get("gold_facts") or []:
            if not isinstance(fact, str) or not fact.strip():
                errors.append(f"{case_id}: invalid gold fact")
            elif fact not in combined:
                errors.append(f"{case_id}: gold fact not found in gold chunks: {fact}")

    cutoffs = data.get("cutoffs")
    if not isinstance(cutoffs, list) or not cutoffs or any(type(value) is not int or value < 1 for value in cutoffs):
        errors.append("cutoffs must contain positive integers")
    return errors


def precision_at_k(ranked: list[str], relevant: set[str], k: int) -> float:
    if k <= 0:
        return 0.0
    return len(set(ranked[:k]) & relevant) / k


def recall_at_k(ranked: list[str], relevant: set[str], k: int) -> float | None:
    if not relevant:
        return None
    return len(set(ranked[:k]) & relevant) / len(relevant)


def hit_at_k(ranked: list[str], relevant: set[str], k: int) -> float | None:
    if not relevant:
        return None
    return 1.0 if set(ranked[:k]) & relevant else 0.0


def reciprocal_rank(ranked: list[str], relevant: set[str], k: int) -> float | None:
    if not relevant:
        return None
    for rank, item in enumerate(ranked[:k], 1):
        if item in relevant:
            return 1.0 / rank
    return 0.0


def ndcg_at_k(ranked: list[str], grades: dict[str, int], k: int) -> float | None:
    if not grades:
        return None
    dcg = 0.0
    for rank, item in enumerate(ranked[:k], 1):
        gain = grades.get(item, 0)
        dcg += (2**gain - 1) / math.log2(rank + 1)
    ideal = sorted(grades.values(), reverse=True)[:k]
    idcg = sum((2**gain - 1) / math.log2(rank + 1) for rank, gain in enumerate(ideal, 1))
    return None if idcg == 0 else dcg / idcg


def resolve_gold(case: dict[str, Any]) -> tuple[set[str], dict[str, int]]:
    relevant: set[str] = set()
    grades: dict[str, int] = {}
    for gold in case.get("gold_chunks") or []:
        resolved = chunk_id(gold["document_id"], gold["chunk"])
        relevant.add(resolved)
        grades[resolved] = 2
    for support in case.get("supporting_chunks") or []:
        resolved = chunk_id(support["document_id"], support["chunk"])
        if resolved not in relevant:
            grades[resolved] = 1
    return relevant, grades


class HttpApi:
    def __init__(self, base_url: str, admin_token: str | None = None, run_token: str | None = None,
                 timeout: float = 90.0):
        self.base_url = base_url.rstrip("/")
        self.admin_token = admin_token
        self.run_token = run_token
        self.timeout = timeout

    def request(self, method: str, path: str, *, body: Any = None, query: dict[str, Any] | None = None,
                admin: bool = False, allow_error: bool = False) -> tuple[int, Any]:
        url = self.base_url + path
        if query:
            url += "?" + urllib.parse.urlencode(query)
        headers = {"Accept": "application/json"}
        token = self.admin_token if admin else self.run_token
        if token:
            headers["Authorization"] = f"Bearer {token}"
        data = None
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json; charset=utf-8"
        request = urllib.request.Request(url, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = response.read().decode("utf-8")
                return response.status, json.loads(payload) if payload else None
        except urllib.error.HTTPError as error:
            payload = error.read().decode("utf-8", errors="replace")
            parsed: Any
            try:
                parsed = json.loads(payload) if payload else None
            except json.JSONDecodeError:
                parsed = {"detail": payload}
            if not allow_error:
                raise RuntimeError(f"{method} {path} -> HTTP {error.code}: {payload[:500]}") from error
            return error.code, parsed

    def health(self) -> None:
        status, payload = self.request("GET", "/health")
        if status != 200 or not isinstance(payload, dict) or payload.get("status") != "ok":
            raise RuntimeError(f"health check failed: HTTP {status}, {payload}")

    def knowledge_stats(self, admin: bool = False) -> int:
        status, payload = self.request("GET", "/knowledge/stats", admin=admin)
        if status != 200:
            raise RuntimeError(f"knowledge stats failed: HTTP {status}")
        return int(payload["total_chunks"])

    def seed(self, data: dict[str, Any]) -> int:
        documents = [
            {
                "title": document["title"],
                "content": document["content"],
                "document_id": document["document_id"],
            }
            for document in data["documents"]
        ]
        status, payload = self.request("POST", "/knowledge/add", body={"documents": documents}, admin=True)
        if status != 200:
            raise RuntimeError(f"knowledge seed failed: HTTP {status}, {payload}")
        return int(payload.get("added_chunks", 0))

    def search(self, query: str, top_k: int) -> dict[str, Any]:
        status, payload = self.request("POST", "/search", query={"query": query, "top_k": top_k})
        if status != 200 or not isinstance(payload, dict):
            raise RuntimeError(f"search failed: HTTP {status}, {payload}")
        return payload

    def chat(self, query: str, user_id: str, conv_id: str, request_id: str) -> dict[str, Any]:
        status, payload = self.request(
            "POST",
            "/chat",
            body={"message": query, "user_id": user_id, "conv_id": conv_id, "client_request_id": request_id},
            allow_error=True,
        )
        if not isinstance(payload, dict):
            return {"status": "failed", "retryable": False, "error_code": "invalid_http_body",
                    "http_status": status, "response": ""}
        payload["_http_status"] = status
        return payload

    def submit_conversation_eval(self, conv_id: str, user_id: str, reference: str, evidence: str) -> str:
        status, payload = self.request(
            "POST",
            "/eval/conversation",
            body={
                "user_id": user_id,
                "conv_id": conv_id,
                "max_turns": 5,
                "reference_answer": reference,
                "evidence": evidence,
            },
            allow_error=True,
        )
        if status != 202 or not isinstance(payload, dict) or not payload.get("job_id"):
            raise RuntimeError(f"conversation evaluation submit failed: HTTP {status}, {payload}")
        return str(payload["job_id"])

    def wait_job(self, job_id: str, timeout_s: float = 180.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            status, payload = self.request("GET", f"/eval/jobs/{urllib.parse.quote(job_id)}", allow_error=True)
            if status != 200 or not isinstance(payload, dict):
                raise RuntimeError(f"evaluation job failed: HTTP {status}, {payload}")
            if payload.get("status") in {"completed", "failed"}:
                return payload
            time.sleep(1.0)
        raise TimeoutError(f"evaluation job {job_id} did not finish within {timeout_s:.0f}s")


def average(values: Iterable[float | None]) -> float | None:
    concrete = [float(value) for value in values if value is not None]
    return round(statistics.mean(concrete), 4) if concrete else None


def summarize_retrieval(records: list[dict[str, Any]], cutoffs: list[int]) -> dict[str, Any]:
    answerable = [record for record in records if record["answerable"] and record.get("retrieved_ids") is not None]
    result: dict[str, Any] = {"answerable_cases": len(answerable), "cutoffs": {}}
    for cutoff in cutoffs:
        result["cutoffs"][str(cutoff)] = {
            "hit_rate": average(record["retrieval_metrics"][str(cutoff)]["hit"] for record in answerable),
            "recall": average(record["retrieval_metrics"][str(cutoff)]["recall"] for record in answerable),
            "precision": average(record["retrieval_metrics"][str(cutoff)]["precision"] for record in answerable),
            "mrr": average(record["retrieval_metrics"][str(cutoff)]["mrr"] for record in answerable),
            "ndcg": average(record["retrieval_metrics"][str(cutoff)]["ndcg"] for record in answerable),
        }

    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in answerable:
        groups[record["category"]].append(record)
    primary = str(max(cutoffs))
    result["by_category"] = {
        category: {
            "cases": len(items),
            "recall": average(item["retrieval_metrics"][primary]["recall"] for item in items),
            "mrr": average(item["retrieval_metrics"][primary]["mrr"] for item in items),
            "ndcg": average(item["retrieval_metrics"][primary]["ndcg"] for item in items),
        }
        for category, items in sorted(groups.items())
    }
    unanswerable = [record for record in records if not record["answerable"] and record.get("retrieved_ids") is not None]
    result["unanswerable_retrieval_nonempty"] = sum(bool(record["retrieved_ids"]) for record in unanswerable)
    return result


def attribution(case: dict[str, Any], search_payload: dict[str, Any] | None, chat_payload: dict[str, Any] | None,
                judge: dict[str, Any] | None, *, accuracy_threshold: float, overall_threshold: float) -> str:
    if search_payload is None:
        return "retrieval_error"
    if not search_payload.get("success"):
        return "retrieval_error"
    retrieved = [result["id"] for result in search_payload.get("results") or []]
    relevant, _ = resolve_gold(case)
    if case["answerable"]:
        hits = len(set(retrieved) & relevant)
        if hits == 0:
            return "retrieval_miss"
        if hits < len(relevant):
            return "partial_retrieval_miss"
    if chat_payload is None:
        return "generation_not_run"
    if chat_payload.get("status") == "failed":
        return "agent_failure"
    if case["answerable"] and not chat_payload.get("knowledge_used"):
        return "agent_skipped_retrieval"
    if chat_payload.get("verified") is False:
        return "answer_verification_failed"
    if judge is None:
        return "not_judged"
    if judge.get("judge_failed"):
        return "judge_unavailable"
    accuracy = judge.get("accuracy")
    overall = judge.get("overall")
    if accuracy is None or overall is None:
        return "judge_unavailable"
    if not chat_payload.get("grounded", False):
        return "ungrounded_generation"
    if float(accuracy) < accuracy_threshold:
        return "incorrect_generation"
    if float(overall) < overall_threshold:
        return "answer_quality_below_threshold"
    return "pass"


def extract_scores(job: dict[str, Any]) -> dict[str, Any]:
    result = job.get("result")
    if not isinstance(result, dict):
        return {"judge_failed": True, "error": "evaluation_job_has_no_result"}
    rows = result.get("results") or []
    if not rows:
        return {"judge_failed": True, "error": "evaluation_job_has_no_turns"}
    row = rows[0]
    scores = row.get("scores") if isinstance(row, dict) else None
    metadata = row.get("metadata") if isinstance(row, dict) else {}
    if not isinstance(scores, dict) or not scores:
        return {"judge_failed": True, "error": str((metadata or {}).get("judge_error", "empty_scores"))}
    return {**scores, "judge_failed": False}


def resolve_evidence(case: dict[str, Any], documents: dict[str, dict[str, Any]]) -> str:
    return "\n".join(
        documents[gold["document_id"]]["content"]
        for gold in case.get("gold_chunks") or []
    )


def group_generation(records: list[dict[str, Any]]) -> dict[str, Any]:
    attempted = [record for record in records if record.get("chat") is not None]
    judged = [record for record in records if record.get("judge") and not record["judge"].get("judge_failed")]
    judged_ids = {id(record) for record in judged}

    def summary(items: list[dict[str, Any]]) -> dict[str, Any]:
        judged_items = [item for item in items if id(item) in judged_ids]
        return {
            "cases": len(items),
            "judged_cases": len(judged_items),
            # Execution and Judge failures stay in the task denominator. Quality
            # means only include valid judge scores.
            "rag_pipeline_pass_rate": average(1.0 if item["attribution"] == "pass" else 0.0 for item in items),
            "answer_pass_rate": average(1.0 if item.get("answer_passed") else 0.0 for item in items),
            "accuracy": average(item["judge"].get("accuracy") for item in judged_items),
            "overall": average(item["judge"].get("overall") for item in judged_items),
        }

    groups: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for record in attempted:
        groups[record["category"]].append(record)
    return {
        "overall": summary(attempted),
        "by_category": {category: summary(items) for category, items in sorted(groups.items())},
    }


def write_report(report: dict[str, Any], output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = output.with_suffix(output.suffix + ".tmp")
    temporary.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    os.replace(temporary, output)


def run_eval(args: argparse.Namespace, data: dict[str, Any]) -> dict[str, Any]:
    errors = validate_dataset(data)
    if errors:
        raise ValueError("dataset validation failed:\n- " + "\n- ".join(errors))

    api = HttpApi(args.base_url, args.admin_token, args.run_token or args.admin_token, args.timeout)
    api.health()
    documents = {document["document_id"]: document for document in data["documents"]}
    if args.seed:
        if args.require_empty_corpus:
            before = api.knowledge_stats(admin=True)
            if before != 0:
                raise RuntimeError(
                    f"corpus is not empty ({before} chunks). Start an isolated instance with "
                    "KNOWLEDGE_SEED_DEMO=false or omit --require-empty-corpus."
                )
        api.seed(data)
    stats = api.knowledge_stats(admin=True)
    expected_chunks = len(data["documents"])
    if args.require_corpus_match and stats != expected_chunks:
        raise RuntimeError(
            f"corpus has {stats} chunks, expected exactly {expected_chunks}. "
            "Use an isolated corpus before trusting retrieval metrics."
        )

    cases = list(data["cases"])
    if args.category:
        cases = [case for case in cases if case["category"] == args.category]
    if args.case_id:
        wanted = set(args.case_id)
        cases = [case for case in cases if case["id"] in wanted]
    if args.limit:
        cases = cases[:args.limit]
    if not cases:
        raise ValueError("no cases selected")

    run_id = f"rag-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')}-{uuid.uuid4().hex[:8]}"
    records: list[dict[str, Any]] = []
    for position, case in enumerate(cases, 1):
        print(f"[{position}/{len(cases)}] {case['id']}: {case['query']}", flush=True)
        relevant, grades = resolve_gold(case)
        search_error = None
        try:
            search_payload = api.search(case["query"], args.top_k)
        except Exception as error:
            search_payload = None
            search_error = str(error)
        retrieved = [result["id"] for result in (search_payload or {}).get("results") or []]
        metrics: dict[str, Any] = {}
        for cutoff in data["cutoffs"]:
            metrics[str(cutoff)] = {
                "hit": hit_at_k(retrieved, relevant, cutoff),
                "recall": recall_at_k(retrieved, relevant, cutoff),
                "precision": precision_at_k(retrieved, relevant, cutoff) if case["answerable"] else None,
                "mrr": reciprocal_rank(retrieved, relevant, cutoff),
                "ndcg": ndcg_at_k(retrieved, grades, cutoff),
            }

        chat_payload = None
        judge = None
        if not args.retrieval_only:
            user_id = f"rag-eval-{data['dataset_version']}"
            conversation_id = f"{run_id}-{position}"
            request_id = f"{run_id}-{position}-turn"
            chat_payload = api.chat(case["query"], user_id, conversation_id, request_id)
            if not args.skip_judge and chat_payload.get("status") == "success":
                evidence = resolve_evidence(case, documents)
                job_id = api.submit_conversation_eval(
                    conversation_id, user_id, case["reference_answer"], evidence
                )
                judge = extract_scores(api.wait_job(job_id, args.judge_timeout))

        label = attribution(
            case,
            search_payload,
            chat_payload,
            judge,
            accuracy_threshold=args.accuracy_threshold,
            overall_threshold=args.overall_threshold,
        )
        answer_passed = bool(
            judge
            and not judge.get("judge_failed")
            and chat_payload
            and chat_payload.get("status") == "success"
            and chat_payload.get("verified") is True
            and chat_payload.get("grounded") is True
            and float(judge.get("accuracy", 0.0)) >= args.accuracy_threshold
            and float(judge.get("overall", 0.0)) >= args.overall_threshold
        )
        records.append({
            "id": case["id"],
            "query": case["query"],
            "category": case["category"],
            "difficulty": case["difficulty"],
            "answerable": case["answerable"],
            "expected_abstain": case["expected_abstain"],
            "gold_ids": sorted(relevant),
            "retrieved_ids": retrieved,
            "search_error": search_error,
            "retrieval_metrics": metrics,
            "chat": chat_payload,
            "judge": judge,
            "answer_passed": answer_passed,
            "attribution": label,
        })

    retrieval = summarize_retrieval(records, data["cutoffs"])
    generation = group_generation(records)
    counts = dict(sorted(Counter(record["attribution"] for record in records).items()))
    primary_cutoff = str(max(data["cutoffs"]))
    recall = retrieval["cutoffs"][primary_cutoff]["recall"]
    pass_rate = generation["overall"]["rag_pipeline_pass_rate"]
    gate_passed = (
        recall is not None
        and recall >= args.min_recall
        and (args.retrieval_only or (pass_rate is not None and pass_rate >= args.min_pass_rate))
    )

    report = {
        "run_id": run_id,
        "generated_at": datetime.now(timezone.utc).isoformat(),
        "base_url": api.base_url,
        "dataset_version": data["dataset_version"],
        "corpus_version": data["corpus_version"],
        "dataset_sha256": sha256_file(Path(args.dataset).resolve()),
        "corpus": {
            "documents": len(data["documents"]),
            "expected_chunks": expected_chunks,
            "observed_chunks": stats,
            "isolated_exact_match": stats == expected_chunks,
        },
        "config": {
            "top_k": args.top_k,
            "cutoffs": data["cutoffs"],
            "retrieval_only": args.retrieval_only,
            "skip_judge": args.skip_judge,
            "accuracy_threshold": args.accuracy_threshold,
            "overall_threshold": args.overall_threshold,
        },
        "retrieval": retrieval,
        "generation": generation,
        "attribution": counts,
        "gate": {
            "passed": gate_passed,
            "min_recall": args.min_recall,
            "observed_recall": recall,
            "min_pass_rate": args.min_pass_rate,
            "observed_pass_rate": pass_rate,
        },
        "cases": records,
    }
    output = Path(args.output) if args.output else DEFAULT_REPORT_DIR / f"{run_id}.json"
    write_report(report, output)
    print(json.dumps({
        "report": str(output.resolve()),
        "retrieval": retrieval["cutoffs"],
        "generation": generation["overall"],
        "attribution": counts,
        "gate": report["gate"],
    }, ensure_ascii=False, indent=2))
    return report


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="EchoMind Java RAG evaluation pipeline")
    parser.add_argument("command", choices=["validate", "run"], nargs="?", default="validate")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--admin-token", default=os.getenv("ECHOMIND_ADMIN_TOKEN"))
    parser.add_argument("--run-token", default=os.getenv("ECHOMIND_RUN_TOKEN"))
    parser.add_argument("--seed", action="store_true", help="upsert the frozen corpus before evaluation")
    parser.add_argument("--require-empty-corpus", action="store_true")
    parser.add_argument("--require-corpus-match", action="store_true", default=True)
    parser.add_argument("--no-require-corpus-match", dest="require_corpus_match", action="store_false")
    parser.add_argument("--top-k", type=int, default=5)
    parser.add_argument("--category", action="append")
    parser.add_argument("--case-id", action="append")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--retrieval-only", action="store_true")
    parser.add_argument("--skip-judge", action="store_true")
    parser.add_argument("--accuracy-threshold", type=float, default=0.8)
    parser.add_argument("--overall-threshold", type=float, default=0.75)
    parser.add_argument("--min-recall", type=float, default=0.8)
    parser.add_argument("--min-pass-rate", type=float, default=0.75)
    parser.add_argument("--timeout", type=float, default=90.0)
    parser.add_argument("--judge-timeout", type=float, default=180.0)
    parser.add_argument("--output")
    args = parser.parse_args(argv)
    if args.top_k < 1 or max(5, args.top_k) > 20:
        parser.error("--top-k must be between 1 and 20")
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    path = Path(args.dataset).resolve()
    data = load_dataset(path)
    errors = validate_dataset(data)
    if errors:
        print("Dataset invalid:", file=sys.stderr)
        for error in errors:
            print(f"- {error}", file=sys.stderr)
        return 2
    print(
        f"Validated {len(data['documents'])} documents, {len(data['cases'])} cases, "
        f"version={data['dataset_version']}"
    )
    if args.command == "validate":
        return 0
    report = run_eval(args, data)
    return 0 if report["gate"]["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
