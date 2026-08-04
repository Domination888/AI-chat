#!/usr/bin/env python3
"""Compare legacy SearXNG top-3 retrieval with the backend Search-RAG endpoint."""

import argparse
import json
import math
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CASES = ROOT / "backend/src/test/resources/search-evaluation.json"


def request_json(url, payload=None, timeout=25):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers={
        "Accept": "application/json",
        "Content-Type": "application/json",
        "User-Agent": "AI-Chat-Search-Evaluation/1.0",
    })
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def legacy_search(base_url, case):
    params = {
        "q": case["query"], "format": "json", "categories": "general",
        "language": case.get("language", "zh-CN"),
        "engines": "brave,duckduckgo,bing,baidu,sogou,360search",
    }
    if case.get("timeRange"):
        params["time_range"] = case["timeRange"]
    data = request_json(base_url.rstrip("/") + "/search?" + urllib.parse.urlencode(params), timeout=15)
    seen = set()
    sources = []
    for item in data.get("results", []):
        url = item.get("url", "")
        key = canonical_url(url)
        if not key or key in seen:
            continue
        seen.add(key)
        sources.append({"title": item.get("title", ""), "url": url})
        if len(sources) == 3:
            break
    return {"status": "ok" if sources else "no_results", "sources": sources}


def candidate_search(base_url, case):
    payload = {key: case[key] for key in
               ("query", "conversationContext", "language", "timeRange") if key in case}
    payload["maxSources"] = 3
    return request_json(base_url.rstrip("/") + "/api/search/test", payload, timeout=25)


def canonical_url(value):
    try:
        parsed = urllib.parse.urlparse(value)
        if parsed.scheme not in ("http", "https") or not parsed.hostname:
            return ""
        return f"{parsed.scheme.lower()}://{parsed.hostname.lower()}{parsed.path.rstrip('/') or '/'}"
    except ValueError:
        return ""


def is_expected(url, domains):
    try:
        host = (urllib.parse.urlparse(url).hostname or "").lower()
    except ValueError:
        return False
    return any(host == domain or host.endswith("." + domain) for domain in domains)


def percentile_95(values):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * 0.95) - 1)]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("baseline", "candidate"), required=True)
    parser.add_argument("--cases", default=str(DEFAULT_CASES))
    parser.add_argument("--searxng-url", default="http://127.0.0.1:8888")
    parser.add_argument("--backend-url", default="http://127.0.0.1:8080")
    args = parser.parse_args()
    cases = json.loads(Path(args.cases).read_text(encoding="utf-8"))

    rows, latencies = [], []
    relevant, judged_slots, valid_urls = 0, 0, 0
    possible_slots, coverage_urls = 0, 0
    all_urls, no_result_correct, no_result_total = 0, 0, 0
    for case in cases:
        started = time.monotonic()
        try:
            response = (legacy_search(args.searxng_url, case) if args.mode == "baseline"
                        else candidate_search(args.backend_url, case))
            error = ""
        except Exception as exc:  # keep the full evaluation running after one failed case
            response, error = {"status": "error", "sources": []}, str(exc)
        latency_ms = round((time.monotonic() - started) * 1000)
        latencies.append(latency_ms)
        sources = response.get("sources") or []
        urls = [source.get("url", "") for source in sources[:3]]
        if case.get("expectNoSources"):
            no_result_total += 1
            no_result_correct += int(not urls)
            precision = None
        else:
            hits = sum(is_expected(url, case.get("expectedDomains", [])) for url in urls)
            denominator = max(1, len(urls))
            relevant += hits
            judged_slots += denominator
            possible_slots += 3
            coverage_urls += len(urls)
            precision = round(hits / denominator, 4)
        all_urls += len(urls)
        valid_urls += sum(bool(canonical_url(url)) for url in urls)
        rows.append({
            "id": case["id"], "status": response.get("status"), "latencyMs": latency_ms,
            "precisionAt3": precision, "urls": urls, "error": error,
        })

    summary = {
        "mode": args.mode,
        "capturedAt": datetime.now(timezone.utc).isoformat(),
        "caseCount": len(cases),
        "precisionAt3": round(relevant / judged_slots, 4) if judged_slots else 0,
        "precisionAt3Definition": "relevant / actually returned among top 3; an empty non-abstention case counts as one miss",
        "coverageAt3": round(coverage_urls / possible_slots, 4) if possible_slots else 0,
        "urlFormatValidity": round(valid_urls / all_urls, 4) if all_urls else 1,
        "noResultAccuracy": round(no_result_correct / no_result_total, 4) if no_result_total else None,
        "latencyP95Ms": percentile_95(latencies),
        "cases": rows,
    }
    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
