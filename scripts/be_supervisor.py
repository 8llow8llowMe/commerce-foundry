#!/usr/bin/env python3
"""Run independent Codex BE issue sessions and reconcile merged PRs.

State and JSONL transcripts live outside the public repository. No command merges a PR.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
COMMON = Path(subprocess.check_output(
    ["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
    cwd=ROOT, text=True, encoding="utf-8"
).strip()).resolve()
REPO = COMMON.parent if COMMON.name == ".git" else ROOT
WORKTREES = REPO.parent / "commerce-foundry-wt"
STATE = REPO.parent / ".commerce-foundry-be-supervisor"
ISSUE_RE = re.compile(r"^\[BE\] (feat|fix|chore|refactor|docs|test|style):")
BRANCH_RE = re.compile(r"^(feature|fix|chore|refactor|docs|test|style)/be/(\d+)-[a-z0-9]+(?:-[a-z0-9]+)*$")
ITEM_RE = re.compile(r"^- \[([ xX])\] (.+)$", re.MULTILINE)
LINK_RE = re.compile(r"Issue Number:\s*#(\d+)", re.IGNORECASE)


def run(*args: str, cwd: Path = REPO, check: bool = True) -> str:
    p = subprocess.run(args, cwd=cwd, text=True, encoding="utf-8", capture_output=True)
    if check and p.returncode:
        raise RuntimeError(f"{' '.join(args)}: {p.stderr.strip() or p.stdout.strip()}")
    return p.stdout.strip()


def gh_json(*args: str) -> object:
    return json.loads(run("gh", *args))


def records() -> list[dict]:
    if not STATE.exists():
        return []
    return [json.loads(p.read_text(encoding="utf-8")) for p in STATE.glob("job-*.json")]


def save(record: dict) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    target = STATE / f"{record['job_id']}.json"
    temp = target.with_suffix(".tmp")
    temp.write_text(json.dumps(record, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(target)


def events(record: dict) -> list[dict]:
    path = Path(record["log"])
    if not path.exists():
        return []
    result = []
    with path.open(encoding="utf-8", errors="replace") as stream:
        for line in stream:
            try:
                result.append(json.loads(line))
            except json.JSONDecodeError:
                continue  # an active process may be writing its last line
    return result


def session_state(record: dict) -> tuple[str, str | None]:
    stream = events(record)
    session = next((e.get("thread_id") for e in stream if e.get("type") == "thread.started"), None)
    session = session or record.get("session_id")
    exit_file = Path(record["exit"])
    if exit_file.exists():
        succeeded = exit_file.read_text(encoding="ascii").strip() == "0"
        completed = any(e.get("type") == "turn.completed" for e in stream)
        return ("finished" if succeeded and completed else "failed"), session
    if any(e.get("type") == "turn.failed" for e in stream):
        return "failed", session
    if not stream:
        return "starting", session
    return "running", session


def worktree_map() -> dict[str, Path]:
    result: dict[str, Path] = {}
    path: Path | None = None
    for line in run("git", "worktree", "list", "--porcelain").splitlines():
        if line.startswith("worktree "):
            path = Path(line[9:]).resolve()
        elif line.startswith("branch refs/heads/") and path:
            result[line.removeprefix("branch refs/heads/")] = path
    return result


def issue_list() -> list[dict]:
    data = gh_json("issue", "list", "--state", "open", "--limit", "500", "--json", "number,title,labels,milestone")
    return [i for i in data if ISSUE_RE.match(i["title"])]


def pr_list(state: str) -> list[dict]:
    return gh_json("pr", "list", "--state", state, "--limit", "500", "--json", "number,headRefName,baseRefName,body,mergedAt,title")


def candidates() -> list[dict]:
    active = {r["issue"] for r in records() if not r.get("reconciled")}
    branches = worktree_map()
    prs = pr_list("open")
    occupied = {int(m.group(2)) for name in branches if (m := BRANCH_RE.match(name))}
    occupied |= {int(m.group(2)) for p in prs if (m := BRANCH_RE.match(p["headRefName"]))}
    return [i for i in issue_list() if i["number"] not in active | occupied]


def show() -> None:
    for r in sorted(records(), key=lambda item: item["issue"]):
        state, session = session_state(r)
        print(f"#{r['issue']} {state:9} session={session or '-'} branch={r['branch']}")
    if queued():
        print("대기열: " + ", ".join(f"#{item['issue']}" for item in queued()))
    print("\n할당 후보:")
    for issue in candidates():
        print(f"#{issue['number']} {issue['title']}")


def assign(issue_number: int, slug: str) -> None:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+){1,3}", slug):
        raise ValueError("slug는 영문 kebab-case 2~4단어여야 합니다")
    issue = gh_json("issue", "view", str(issue_number), "--json", "number,title,state,body")
    match = ISSUE_RE.match(issue["title"])
    if issue["state"] != "OPEN" or not match:
        raise ValueError("열린 [BE] 이슈만 할당할 수 있습니다")
    if issue_number not in {i["number"] for i in candidates()}:
        raise ValueError("이미 세션·워크트리·PR이 연결된 이슈입니다")
    if any(not r.get("reconciled") for r in records()):
        raise ValueError("이전 작업의 PR 머지·정리가 완료되지 않았습니다")
    kind = "feature" if match.group(1) == "feat" else match.group(1)
    branch = f"{kind}/be/{issue_number}-{slug}"
    path = (WORKTREES / f"{issue_number}-{slug}").resolve()
    if path.parent != WORKTREES.resolve() or path.exists():
        raise ValueError("워크트리 경로가 이미 있거나 허용 범위 밖입니다")
    run("git", "fetch", "origin")
    run("git", "worktree", "add", "--no-track", "-b", branch, str(path), "origin/develop")
    prompt = (
        f"이 저장소의 [BE] 이슈 #{issue_number} ({issue['title']})를 맡으세요. "
        "루트 및 backend/AGENTS.md와 정본 문서를 읽고 이슈 명세→계획→구현→검증 순서로 진행하세요. "
        "이 워크트리만 편집하고 다른 세션의 파일·브랜치·워크트리를 건드리지 마세요. "
        "결제·주문·재고면 payment-guard와 order-flow-guard를 적용하세요. "
        "검증과 리뷰 후 이슈 번호가 포함된 커밋을 만들고 develop 대상 PR을 생성하세요. "
        "PR에는 Issue Number 및 실제 완료한 이슈 체크박스 문구를 '완료된 이슈 항목' 절에 정확히 적으세요. "
        "PR을 머지하거나 이슈를 닫거나 워크트리를 정리하지 마세요. "
        "다른 쓰기 세션과 동시에 작업하지 마세요. 최종 응답에 PR URL, 검증 명령·결과, 남은 항목을 적으세요."
    )
    STATE.mkdir(parents=True, exist_ok=True)
    job_id = f"job-{issue_number}-{slug}"
    if (STATE / f"{job_id}.json").exists():
        raise ValueError("같은 작업 ID가 기록에 이미 있습니다")
    log = STATE / f"{job_id}-turn-1.jsonl"
    prompt_file = STATE / f"{job_id}-turn-1.prompt"
    prompt_file.write_text(prompt, encoding="utf-8")
    proc = subprocess.Popen(
        [sys.executable, str(Path(__file__).resolve()), "_worker", str(path), str(log), str(prompt_file)],
        cwd=path, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    record = {"job_id": job_id, "issue": issue_number, "branch": branch,
              "worktree": str(path), "log": str(log),
              "exit": str(prompt_file.with_suffix(".exit")),
              "pid": proc.pid, "turn": 1, "reconciled": False}
    save(record)
    print(f"#{issue_number} 할당: PID {proc.pid}, {branch}, {path}")


def worker(path: Path, log: Path, prompt_file: Path, session: str | None = None) -> None:
    exit_file = prompt_file.with_suffix(".exit")
    try:
        command = shutil.which("codex.cmd" if os.name == "nt" else "codex")
        if not command:
            raise RuntimeError("Codex CLI를 찾을 수 없습니다")
        prompt = prompt_file.read_text(encoding="utf-8")
        args = [command, "exec", "--json"]
        if session:
            args += ["resume", session, prompt]
        else:
            args += ["-C", str(path), prompt]
        with log.open("w", encoding="utf-8") as output:
            result = subprocess.run(args,
                                    cwd=path, stdin=subprocess.DEVNULL, stdout=output,
                                    stderr=subprocess.STDOUT)
        exit_file.write_text(str(result.returncode), encoding="ascii")
    except Exception as exc:
        with log.open("a", encoding="utf-8") as output:
            output.write(json.dumps({"type": "supervisor.error", "message": str(exc)}, ensure_ascii=False) + "\n")
        exit_file.write_text("1", encoding="ascii")


def queue_file() -> Path:
    return STATE / "queue.json"


def queued() -> list[dict]:
    return json.loads(queue_file().read_text(encoding="utf-8")) if queue_file().exists() else []


def enqueue(issue: int, slug: str) -> None:
    if issue not in {i["number"] for i in candidates()}:
        raise ValueError("할당 후보가 아닌 이슈입니다")
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+){1,3}", slug):
        raise ValueError("slug는 영문 kebab-case 2~4단어여야 합니다")
    jobs = queued()
    if any(job["issue"] == issue for job in jobs):
        raise ValueError("이미 대기열에 있습니다")
    STATE.mkdir(parents=True, exist_ok=True)
    jobs.append({"issue": issue, "slug": slug})
    queue_file().write_text(json.dumps(jobs, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"#{issue} 대기열 추가")


def dispatch_queue() -> None:
    jobs = queued()
    if not jobs or any(not r.get("reconciled") for r in records()):
        return
    job = jobs[0]
    assign(job["issue"], job["slug"])
    queue_file().write_text(json.dumps(jobs[1:], ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def resume(issue: int, instruction: str) -> None:
    matches = [r for r in records() if r["issue"] == issue and not r.get("reconciled")]
    if len(matches) != 1:
        raise ValueError("재개할 작업 세션이 정확히 하나여야 합니다")
    record = matches[0]
    state, session = session_state(record)
    if state != "finished" or not session:
        raise ValueError("완료된 세션 ID가 있어야 재개할 수 있습니다")
    if any(session_state(r)[0] in {"starting", "running"} for r in records()
           if r["job_id"] != record["job_id"] and not r.get("reconciled")):
        raise ValueError("다른 쓰기 세션이 실행 중입니다")
    turn = record["turn"] + 1
    path = Path(record["worktree"])
    if worktree_map().get(record["branch"]) != path.resolve():
        raise ValueError("작업 워크트리를 찾을 수 없습니다")
    prompt_file = STATE / f"{record['job_id']}-turn-{turn}.prompt"
    log = prompt_file.with_suffix(".jsonl")
    prompt_file.write_text(instruction, encoding="utf-8")
    proc = subprocess.Popen(
        [sys.executable, str(Path(__file__).resolve()), "_worker", str(path), str(log), str(prompt_file), session],
        cwd=path, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    record.update({"turn": turn, "log": str(log), "exit": str(prompt_file.with_suffix(".exit")),
                   "pid": proc.pid, "session_id": session})
    save(record)
    print(f"#{issue} 세션 {session} 재개: PID {proc.pid}")


def completed_items(issue_body: str, pr_body: str) -> list[str]:
    section = re.search(r"(?mi)^#{1,4}\s*완료된 이슈 항목\s*$([\s\S]*?)(?=^#{1,4}\s|\Z)", pr_body)
    if not section:
        return []
    issue_texts = {text.strip() for _, text in ITEM_RE.findall(issue_body)}
    return [text.strip() for checked, text in ITEM_RE.findall(section.group(1))
            if checked.lower() == "x" and text.strip() in issue_texts]


def checked_body(body: str, items: list[str]) -> str:
    for item in items:
        body = body.replace(f"- [ ] {item}", f"- [x] {item}")
    return body


def matching_merged_pr(record: dict, merged: list[dict]) -> dict | None:
    return next((p for p in merged if p["baseRefName"] == "develop"
                 and p["headRefName"] == record["branch"]
                 and str(record["issue"]) in LINK_RE.findall(p["body"] or "")), None)


def safe_cleanup(record: dict) -> None:
    branch, path = record["branch"], Path(record["worktree"]).resolve()
    if path.parent != WORKTREES.resolve() or worktree_map().get(branch) != path:
        raise ValueError("등록된 작업 워크트리와 경로가 일치하지 않습니다")
    if run("git", "status", "--porcelain", cwd=path):
        raise ValueError("워크트리에 미커밋 변경이 있습니다")
    cherry = run("git", "cherry", "origin/develop", branch)
    if any(line.startswith("+ ") for line in cherry.splitlines()):
        raise ValueError("develop에 반영되지 않은 커밋이 있습니다")
    run("git", "worktree", "remove", str(path))
    run("git", "branch", "-D", branch)


def reconcile() -> None:
    run("git", "fetch", "--prune", "origin")
    merged = pr_list("merged")
    for record in records():
        if record.get("reconciled"):
            continue
        pr = matching_merged_pr(record, merged)
        if not pr:
            continue
        if session_state(record)[0] != "finished":
            print(f"#{record['issue']}: PR #{pr['number']} 머지됨, 작업 세션 종료 확인 대기")
            continue
        issue = gh_json("issue", "view", str(record["issue"]), "--json", "body,state")
        items = completed_items(issue["body"], pr["body"] or "")
        if not items:
            print(f"#{record['issue']}: PR #{pr['number']} 머지 확인, 완료 항목 명시 없음. 수동 검토 필요")
            continue
        updated = checked_body(issue["body"], items)
        if updated != issue["body"]:
            body_file = STATE / f"issue-{record['issue']}-body.md"
            body_file.write_text(updated, encoding="utf-8", newline="\n")
            run("gh", "issue", "edit", str(record["issue"]), "--body-file", str(body_file))
            body_file.unlink()
        if not record.get("cleaned"):
            safe_cleanup(record)
            record["cleaned"] = True
            save(record)
        if not any(checked == " " for checked, _ in ITEM_RE.findall(updated)):
            run("gh", "issue", "close", str(record["issue"]))
        record["reconciled"] = True
        record["merged_pr"] = pr["number"]
        save(record)
        print(f"#{record['issue']}: PR #{pr['number']} 반영, {len(items)}개 체크, 워크트리 정리")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("status")
    job = sub.add_parser("assign")
    job.add_argument("issue", type=int)
    job.add_argument("slug")
    queued_parser = sub.add_parser("queue")
    queued_parser.add_argument("issue", type=int)
    queued_parser.add_argument("slug")
    followup = sub.add_parser("resume")
    followup.add_argument("issue", type=int)
    followup.add_argument("instruction")
    sub.add_parser("reconcile")
    watch = sub.add_parser("watch")
    watch.add_argument("--interval", type=int, default=300)
    private = sub.add_parser("_worker")
    private.add_argument("path", type=Path)
    private.add_argument("log", type=Path)
    private.add_argument("prompt_file", type=Path)
    private.add_argument("session", nargs="?")
    args = parser.parse_args()
    if args.command == "status":
        show()
    elif args.command == "assign":
        assign(args.issue, args.slug)
    elif args.command == "queue":
        enqueue(args.issue, args.slug)
    elif args.command == "resume":
        resume(args.issue, args.instruction)
    elif args.command == "reconcile":
        reconcile()
    elif args.command == "_worker":
        worker(args.path, args.log, args.prompt_file, args.session)
    else:
        if args.interval < 30:
            parser.error("interval은 30초 이상이어야 합니다")
        while True:
            try:
                reconcile()
                dispatch_queue()
                show()
            except Exception as exc:
                print(f"감독 오류: {exc}", file=sys.stderr)
            time.sleep(args.interval)


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError) as exc:
        print(f"오류: {exc}", file=sys.stderr)
        raise SystemExit(1)
