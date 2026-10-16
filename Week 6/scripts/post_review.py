#!/usr/bin/env python3
"""Постинг inline-ревью в PR через Reviews API. Только stdlib.
Env: GITHUB_TOKEN, GITHUB_REPOSITORY (owner/repo), PR_NUMBER, HEAD_SHA, REVIEW_FILE.
review.json — выход `cli review --format json` (ReviewResult).
"""
import json
import os
import sys
import urllib.request

API = "https://api.github.com"


def api(method: str, path: str, token: str, payload: dict | None = None) -> tuple[int, str]:
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(
        API + path, data=data, method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    )
    try:
        with urllib.request.urlopen(req) as r:
            return r.status, r.read().decode()
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode()


def main() -> int:
    token = os.environ.get("GITHUB_TOKEN", "").strip()
    repo = os.environ.get("GITHUB_REPOSITORY", "").strip()
    pr = os.environ.get("PR_NUMBER", "").strip()
    sha = os.environ.get("HEAD_SHA", "").strip()
    path = os.environ.get("REVIEW_FILE", "review.json")
    if not token or not repo or not pr or not sha:
        print("нужны GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, HEAD_SHA", file=sys.stderr)
        return 2
    try:
        review = json.load(open(path, encoding="utf-8"))
    except Exception as e:
        print(f"не читается {path}: {e}", file=sys.stderr)
        return 2

    summary = str(review.get("summary", "") or "")[:6000] or "AI-ревью: замечаний нет."
    fresh = review.get("freshness", {}) or {}
    if fresh.get("warning"):
        summary = f"⚠ RAG: {fresh['warning']}\n\n{summary}"
    comments = []
    for c in review.get("comments", []) or []:
        try:
            comments.append({
                "path": str(c["path"]),
                "line": int(c["line"]),
                "side": "RIGHT",
                "body": f"[{c.get('severity', 'comment')}] {c.get('body', '')}"[:2000],
            })
        except Exception:
            continue
    comments = comments[:40]

    payload = {"commit_id": sha, "event": "COMMENT", "body": summary[:6000], "comments": comments}
    code, body = api("POST", f"/repos/{repo}/pulls/{pr}/reviews", token, payload)
    print(f"reviews API: {code}")
    if code not in (200, 201):
        print(body[:2000])
        # Фолбэк: общий коммент без inline (напр. строки уже не на "+"-позициях).
        code2, body2 = api("POST", f"/repos/{repo}/issues/{pr}/comments", token, {"body": summary[:6000]})
        print(f"fallback issue comment: {code2}")
        if code2 not in (200, 201):
            print(body2[:2000])
            return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
