#!/usr/bin/env python3
import json
import threading
import time
import urllib.error
import urllib.request

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


TARGET_BASE = "http://127.0.0.1:1234"
PORT = 12345

lock = threading.Lock()
events = []


def classify(body: bytes, path: str) -> str:
    if "/embeddings" in path:
        return "embedding"
    try:
        payload = json.loads(body.decode("utf-8") or "{}")
    except Exception:
        return "unknown"
    messages = payload.get("messages") or []
    if not messages:
        return "unknown"
    text = "\n".join(str(m.get("content", "")) for m in messages[-3:] if isinstance(m, dict))
    if "Your task is to extract memories" in text or "memory_list" in text:
        return "memos_extract"
    if "hallucination" in text.lower() or "rewritten" in text.lower() or "memories_inline" in text:
        return "memos_filter"
    if "Parsing Goal" in text or "task" in text and "conversation" in text and "context" in text:
        return "memos_search_parse"
    if "is_complex" in text or "sub_questions" in text:
        return "memos_search_cot"
    return "chat_or_other"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def do_GET(self):
        if self.path == "/__stats":
            self.write_json({"events": events})
            return
        if self.path == "/__reset":
            with lock:
                events.clear()
            self.write_json({"ok": True})
            return
        self.proxy()

    def do_POST(self):
        self.proxy()

    def write_json(self, obj):
        data = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def proxy(self):
        length = int(self.headers.get("content-length") or "0")
        body = self.rfile.read(length) if length else b""
        target = TARGET_BASE + self.path
        headers = {k: v for k, v in self.headers.items() if k.lower() not in {"host", "content-length"}}
        start = time.time()
        status = 502
        resp_body = b""
        resp_headers = {}
        try:
            req = urllib.request.Request(target, data=body if self.command != "GET" else None, headers=headers, method=self.command)
            with urllib.request.urlopen(req, timeout=600) as resp:
                status = resp.status
                resp_body = resp.read()
                resp_headers = dict(resp.headers.items())
        except urllib.error.HTTPError as e:
            status = e.code
            resp_body = e.read()
            resp_headers = dict(e.headers.items())
        except Exception as e:
            resp_body = str(e).encode("utf-8")
        elapsed_ms = int((time.time() - start) * 1000)

        if "/chat/completions" in self.path or "/embeddings" in self.path:
            event = {
                "ts": time.time(),
                "path": self.path,
                "kind": classify(body, self.path),
                "status": status,
                "elapsed_ms": elapsed_ms,
            }
            try:
                payload = json.loads(body.decode("utf-8") or "{}")
                event["model"] = payload.get("model")
                event["stream"] = payload.get("stream")
            except Exception:
                pass
            with lock:
                events.append(event)

        self.send_response(status)
        skip = {"transfer-encoding", "connection", "content-encoding", "content-length"}
        for k, v in resp_headers.items():
            if k.lower() not in skip:
                self.send_header(k, v)
        self.send_header("Content-Length", str(len(resp_body)))
        self.end_headers()
        self.wfile.write(resp_body)

    def log_message(self, fmt, *args):
        return


if __name__ == "__main__":
    server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    print(f"LLM counting proxy listening on http://127.0.0.1:{PORT}, forwarding to {TARGET_BASE}", flush=True)
    server.serve_forever()
