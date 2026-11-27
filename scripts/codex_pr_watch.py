#!/usr/bin/env python3
"""Observe registered PRs once and write durable supervisor inbox events."""
from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime
import hashlib
import json
from pathlib import Path
import sqlite3
import subprocess
import sys

from codex_team import ROOT, Team, state_path


REPO = "8llow8llowMe/commerce-foundry"
LIST_FIELDS = ("number,state,headRefName,headRefOid,headRepositoryOwner,"
               "isCrossRepository,isDraft,createdAt,url,mergeCommit")
DETAIL_FIELDS = "headRefOid,mergeable,mergeStateStatus,statusCheckRollup"
FAILED = {"FAILURE", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED"}
PASSED = {"SUCCESS", "SKIPPED", "NEUTRAL"}
FOLLOWUPS = {
    "merged": ("사용자 머지를 감지했습니다. develop 통합, 정확한 PR HEAD, 검증과 소유 해제를 대조하세요. "
               "clean 상태와 반영을 확인한 워크트리·브랜치만 정리하고, 실제 완료 항목만 이슈에 반영하세요."),
    "conflict": "충돌 범위와 담당 슬롯을 확인하고 담당 세션에 충돌 해결과 재검증을 요청하세요.",
    "failed": "실패 로그를 확인하고 담당 세션에 최소 수정, 재검증과 같은 PR 갱신을 요청하세요.",
}


def gh_json(*arguments):
    result = subprocess.run(["gh", "pr", *arguments, "--repo", REPO], cwd=ROOT,
                            capture_output=True, text=True, encoding="utf-8", timeout=25)
    if result.returncode:
        raise RuntimeError(f"GitHub PR {arguments[0]} 조회 실패: {result.stderr.strip()}")
    return json.loads(result.stdout)


def match_pr(pr, config, since):
    job = config["branches"].get(pr["headRefName"])
    owner = (pr.get("headRepositoryOwner") or {}).get("login", "").casefold()
    if job and not pr["isCrossRepository"] and not pr["isDraft"] and owner == REPO.split("/")[0].casefold():
        if datetime.fromisoformat(pr["createdAt"]) >= since:
            return job
    return None


def events(pr, fetch):
    number, head = pr["number"], pr["headRefOid"]
    if pr["state"] == "MERGED":
        merged = (pr.get("mergeCommit") or {}).get("oid")
        if merged:
            yield "merged", f"merged:{number}:{merged}", f"머지 감지 · {merged}"
        return
    if pr["state"] != "OPEN":
        return
    yield "opened", f"opened:{number}", "열린 PR 감지"
    details = fetch("view", str(number), "--json", DETAIL_FIELDS)
    if details["headRefOid"] != head:
        return
    if details["mergeable"] == "CONFLICTING" or details["mergeStateStatus"] == "DIRTY":
        yield "conflict", f"conflict:{number}:{head}", "머지 충돌 감지"
    checks = details.get("statusCheckRollup") or []
    failures = sorted({check.get("name") or check.get("context") or "check" for check in checks
                       if check.get("conclusion") in FAILED or check.get("state") in {"FAILURE", "ERROR"}})
    if failures:
        names = ", ".join(failures)
        yield "failed", f"failed:{number}:{head}:{names}", f"CI 실패 감지 · {names}"
    elif checks and all((check.get("status") == "COMPLETED" and check.get("conclusion") in PASSED)
                        or check.get("state") == "SUCCESS" for check in checks):
        yield "ready", f"ready:{number}:{head}", "CI 완료 감지 · PR HEAD와 검증·리뷰 대조 필요"


def event_key(key):
    # A long list of failing checks may exceed the team's key limit.
    return hashlib.sha256(key.encode("utf-8")).hexdigest()


def legacy_events(state):
    for key, entry in state.items():
        parts = key.split(":", 2)
        kind = parts[0]
        if len(parts) >= 2 and parts[1].isdecimal() and kind in (*FOLLOWUPS, "opened", "ready"):
            if mid := entry.get("message"):
                yield mid, event_key(key), parts[1], kind in FOLLOWUPS


def record_prs(team, config, legacy_state, fetch=gh_json):
    since = datetime.fromisoformat(config["since"])
    if since.tzinfo is None:
        raise ValueError("감시 시작 시각 since에 시간대가 필요합니다")
    team.adopt_pr_events(legacy_events(legacy_state))
    prs = fetch("list", "--state", "all", "--base", "develop", "--limit", "100",
                "--json", LIST_FIELDS)
    observed = recorded = 0
    for pr in prs:
        job = match_pr(pr, config, since)
        if job is None:
            continue
        observed += 1
        for kind, key, summary in events(pr, fetch):
            work = f"#{job['issue']} / PR #{pr['number']}"
            body = (f"{summary}\n{job['role']} · {work} · {pr['headRefName']} · HEAD {pr['headRefOid']}\n"
                    f"{pr['url']}")
            if kind in FOLLOWUPS:
                body += "\n" + FOLLOWUPS[kind]
            _, created = team.record_pr_event(body, work, event_key(key), action_required=kind in FOLLOWUPS)
            recorded += created
    return observed, recorded


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", required=True, type=Path, help="since 및 branches 등록 JSON")
    parser.add_argument("--legacy-state", type=Path, help="기존 감시 JSON의 저장된 메시지를 재사용")
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding="utf-8"))
    legacy = json.loads(args.legacy_state.read_text(encoding="utf-8")) if args.legacy_state else {}
    with closing(Team(state_path())) as team:
        observed, recorded = record_prs(team, config, legacy)
    print(f"관측 PR {observed}건 · 새 inbox 이벤트 {recorded}건")


if __name__ == "__main__":
    try:
        main()
    except (KeyError, ValueError, RuntimeError, OSError, sqlite3.Error, subprocess.SubprocessError) as exc:
        print(f"감시 오류: {exc}", file=sys.stderr)
        raise SystemExit(1)
