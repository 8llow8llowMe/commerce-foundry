#!/usr/bin/env python3
"""Coordinate existing independent Codex CLI threads through `codex queue`.

Only registered threads receive work. PR merge is always performed by the user.
State lives outside this public repository.
"""

from __future__ import annotations

import argparse
from contextlib import contextmanager, nullcontext
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time

import be_supervisor as shared


REPO = shared.REPO
WORKTREES = shared.WORKTREES
STATE = REPO.parent / ".commerce-foundry-be-queue"
THREAD_RE = re.compile(r"^[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}$", re.I)
BRANCH_RE = re.compile(r"^(?:feature|fix|chore|refactor|docs|test|style)/be/(\d+)-([a-z0-9]+(?:-[a-z0-9]+)*)$")
ISSUE_LINK_RE = re.compile(r"(?:Issue Number:|Refs|Closes)\s*#(\d+)", re.I)
SLOTS = {"S1", "S2", "S3", "S4", "S5", "S6"}


def state_file() -> Path:
    return STATE / "workers.json"


@contextmanager
def state_lock():
    """Serialize changes made by the watcher and interactive supervisor commands."""
    STATE.mkdir(parents=True, exist_ok=True)
    with (STATE / "workers.lock").open("a+b") as stream:
        if stream.tell() == 0:
            stream.write(b"0")
            stream.flush()
        stream.seek(0)
        if os.name == "nt":
            import msvcrt
            msvcrt.locking(stream.fileno(), msvcrt.LK_LOCK, 1)
        else:
            import fcntl
            fcntl.flock(stream.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            stream.seek(0)
            if os.name == "nt":
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)


def read_state() -> dict:
    if not state_file().exists():
        return {"workers": {}, "queue": []}
    try:
        return json.loads(state_file().read_text(encoding="utf-8"))
    except (UnicodeError, json.JSONDecodeError):
        backup = STATE / "workers.json.bak"
        if backup.exists():
            print("상태 파일이 손상돼 마지막 정상 백업을 읽습니다", file=sys.stderr)
            return json.loads(backup.read_text(encoding="utf-8"))
        raise RuntimeError("감독 상태 파일이 손상됐고 정상 백업이 없습니다")


def write_state(data: dict) -> None:
    STATE.mkdir(parents=True, exist_ok=True)
    content = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    json.loads(content)
    tmp = STATE / "workers.json.tmp"
    if state_file().exists():
        try:
            json.loads(state_file().read_text(encoding="utf-8"))
            backup_tmp = STATE / "workers.json.bak.tmp"
            shutil.copy2(state_file(), backup_tmp)
            backup_tmp.replace(STATE / "workers.json.bak")
        except (UnicodeError, json.JSONDecodeError):
            pass
    tmp.write_text(content, encoding="utf-8", newline="\n")
    json.loads(tmp.read_text(encoding="utf-8"))
    tmp.replace(state_file())


def run(*args: str, cwd: Path = REPO) -> str:
    return shared.run(*args, cwd=cwd)


def register(slot: str, thread: str) -> None:
    if slot not in SLOTS or not THREAD_RE.fullmatch(thread):
        raise ValueError("S1~S6와 UUID 세션 ID가 필요합니다")
    data = read_state()
    if any(w["thread"] == thread for s, w in data["workers"].items() if s != slot):
        raise ValueError("같은 세션 ID를 두 슬롯에 넣을 수 없습니다")
    current = data["workers"].get(slot)
    if current and current.get("job") and not current["job"].get("reconciled"):
        raise ValueError("진행 중인 슬롯을 바꿀 수 없습니다")
    data["workers"][slot] = {"thread": thread, "job": None}
    write_state(data)
    print(f"{slot} 세션 {thread} 등록")


def issue(number: int) -> dict:
    found = shared.gh_json("issue", "view", str(number), "--json", "number,title,state,body,labels")
    if found["state"] != "OPEN" or not shared.ISSUE_RE.match(found["title"]):
        raise ValueError(f"#{number}: 열린 [BE] 이슈가 아닙니다")
    return found


def services(info: dict) -> set[str]:
    return {item["name"] for item in info.get("labels", [])
            if item["name"].startswith("backend-") and item["name"] != "backend-docs"}


def branch_worktree(branch: str) -> Path:
    match = BRANCH_RE.fullmatch(branch)
    if not match:
        raise ValueError("[BE] 이슈 번호가 들어간 작업 브랜치가 필요합니다")
    path = (WORKTREES / f"{match.group(1)}-{match.group(2)}").resolve()
    if path.parent != WORKTREES.resolve():
        raise ValueError("워크트리가 허용된 경로 밖입니다")
    return path


def prepare_remote_branch(branch: str) -> Path:
    path = branch_worktree(branch)
    mapped = shared.worktree_map().get(branch)
    if mapped:
        if mapped != path:
            raise ValueError("동일 브랜치가 다른 워크트리에 있습니다")
        if run("git", "status", "--porcelain", cwd=path):
            raise ValueError("기존 워크트리에 미커밋 변경이 있습니다")
        return path
    if path.exists():
        raise ValueError("목표 워크트리 폴더가 이미 있습니다")
    run("git", "fetch", "origin")
    run("git", "rev-parse", "--verify", f"refs/remotes/origin/{branch}")
    run("git", "worktree", "add", "--no-track", "-b", branch, str(path), f"origin/{branch}")
    return path


def queue_message(thread: str, path: Path, message: str) -> str:
    command = shutil.which("codex.cmd" if sys.platform == "win32" else "codex")
    if not command:
        raise RuntimeError("Codex CLI를 찾을 수 없습니다")
    return run(command, "queue", "--thread", thread, "--message", message,
               "--resume", "-C", str(path))


def worker_prompt(number: int, title: str, branch: str, handoff: str | None, note: str = "") -> str:
    source = f"먼저 `{handoff}`의 인계를 읽으세요. " if handoff else "먼저 이슈 본문을 읽으세요. "
    path = branch_worktree(branch)
    message = (
        f"[관리 감독 세션 배정] #{number} {title}. 작업 브랜치 `{branch}`. 작업 경로 `{path}`. {source}"
        "이 터미널 세션은 이 이슈의 독립 작업자입니다. 세션 기본 cwd가 루트여도 실제 편집·git·빌드는 지정 워크트리에서만 하세요. 다른 세션의 브랜치·파일을 건드리지 마세요. "
        "$dev-orchestrator를 적용해 TYPE/T0~T3를 판정하고 명세→계획→구현→검증→검토를 진행하세요. "
        "backend/AGENTS.md와 정본 docs를 먼저 읽으세요. 결제·주문·재고면 $payment-guard와 $order-flow-guard를 적용하세요. "
        "모듈 검사와 필요한 리뷰를 끝낸 뒤 $pr 스킬로 develop 대상 PR을 올리세요. "
        "PR에 `Issue Number: #번호`와 `## 완료된 이슈 항목` 아래 실제 끝낸 이슈 체크박스의 정확한 문구를 `- [x]`로 적으세요. "
        "미검증을 통과로 보고하지 말고 PR을 직접 머지하지 마세요. 사용자 머지 후 정리는 관리 세션이 합니다. "
        "PR을 올린 뒤 본인 PR의 CI가 끝날 때까지 확인하세요. 실패한 job·테스트 원인을 고쳐 관련 검증을 다시 돌리고 같은 PR에 푸시해 모든 필수 검사가 통과한 뒤 완료 보고하세요. "
        "결과는 PR URL, 실행한 검증, 남은 항목, 후속 이슈로 보고하세요."
    )
    return message + (f" 추가 인계 경계: {note}" if note else "")


def assign(slot: str, number: int, branch: str, handoff: str | None, note: str = "") -> None:
    data = read_state()
    worker = data["workers"].get(slot)
    if not worker:
        raise ValueError(f"{slot} 등록이 필요합니다")
    if worker.get("job") and not worker["job"].get("reconciled"):
        raise ValueError(f"{slot}의 이전 PR 머지·정리가 끝나지 않았습니다")
    match = BRANCH_RE.fullmatch(branch)
    if not match or int(match.group(1)) != number:
        raise ValueError("브랜치의 이슈 번호가 맞지 않습니다")
    info = issue(number)
    if any(w.get("job") and not w["job"].get("reconciled") and w["job"]["issue"] == number
           for w in data["workers"].values()):
        raise ValueError("다른 세션이 같은 이슈를 맡고 있습니다")
    path = prepare_remote_branch(branch)
    if handoff:
        if not (path / handoff).is_file():
            raise ValueError(f"handoff 파일이 없습니다: {handoff}")
    prompt = worker_prompt(number, info["title"], branch, handoff, note)
    response = queue_message(worker["thread"], path, prompt)
    worker["job"] = {"issue": number, "branch": branch, "worktree": str(path),
                     "handoff": handoff, "reconciled": False}
    write_state(data)
    print(f"{slot} → #{number} {branch}: {response or '메시지 큐에 등록'}")


def track(slot: str, number: int, branch: str) -> None:
    """Record a task that was already queued before this CLI knew its thread ID."""
    data = read_state()
    worker = data["workers"].get(slot)
    if not worker or worker.get("job"):
        raise ValueError("등록된 빈 슬롯이 필요합니다")
    match = BRANCH_RE.fullmatch(branch)
    if not match or int(match.group(1)) != number:
        raise ValueError("브랜치의 이슈 번호가 맞지 않습니다")
    path = branch_worktree(branch)
    if shared.worktree_map().get(branch) != path:
        raise ValueError("등록된 작업 워크트리를 찾을 수 없습니다")
    issue(number)
    worker["job"] = {"issue": number, "branch": branch, "worktree": str(path),
                     "handoff": None, "reconciled": False}
    write_state(data)
    print(f"{slot} → #{number} 추적 등록")


def enqueue(slot: str, number: int, slug: str) -> None:
    if slot not in SLOTS or not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+){1,3}", slug):
        raise ValueError("슬롯과 영문 kebab-case 2~4단어가 필요합니다")
    issue(number)
    data = read_state()
    if slot not in data["workers"]:
        raise ValueError("먼저 세션을 등록하세요")
    if any(item["issue"] == number for item in data["queue"]):
        raise ValueError("이미 대기열에 있는 이슈입니다")
    data["queue"].append({"slot": slot, "issue": number, "slug": slug})
    write_state(data)
    print(f"{slot} 후속 대기열: #{number}")


def assign_new(slot: str, number: int, slug: str) -> None:
    """Start an issue without a prior handoff branch after the current PR is reconciled."""
    data = read_state()
    worker = data["workers"].get(slot)
    if not worker or (worker.get("job") and not worker["job"].get("reconciled")):
        raise ValueError("빈 작업 슬롯이 아닙니다")
    info = issue(number)
    if any(w.get("job") and not w["job"].get("reconciled") and w["job"]["issue"] == number
           for w in data["workers"].values()):
        raise ValueError("다른 세션이 같은 이슈를 맡고 있습니다")
    kind = shared.ISSUE_RE.match(info["title"]).group(1)
    branch = f"{'feature' if kind == 'feat' else kind}/be/{number}-{slug}"
    path = branch_worktree(branch)
    if path.exists() or branch in shared.worktree_map():
        raise ValueError("이슈 워크트리·브랜치가 이미 있습니다")
    occupied = [name for name in shared.worktree_map() if (m := BRANCH_RE.fullmatch(name))
                and int(m.group(1)) == number]
    occupied += [pr["headRefName"] for pr in shared.pr_list("open")
                 if (m := BRANCH_RE.fullmatch(pr["headRefName"])) and int(m.group(1)) == number]
    if occupied:
        raise ValueError(f"같은 이슈의 기존 작업이 있습니다: {occupied[0]}")
    run("git", "fetch", "origin")
    remote_branches = run("git", "branch", "-r", "--format=%(refname:short)").splitlines()
    if any((m := BRANCH_RE.fullmatch(ref.removeprefix("origin/")))
           and int(m.group(1)) == number for ref in remote_branches):
        raise ValueError("같은 이슈의 원격 작업 브랜치가 이미 있습니다")
    run("git", "worktree", "add", "--no-track", "-b", branch, str(path), "origin/develop")
    response = queue_message(worker["thread"], path, worker_prompt(number, info["title"], branch, None))
    worker["job"] = {"issue": number, "branch": branch, "worktree": str(path),
                     "handoff": None, "reconciled": False}
    write_state(data)
    print(f"{slot} → #{number} {branch}: {response or '메시지 큐에 등록'}")


def dispatch_queue() -> None:
    data = read_state()
    remaining = []
    active_services = set()
    for worker in data["workers"].values():
        job = worker.get("job")
        if job and not job.get("reconciled"):
            active_services |= services(shared.gh_json(
                "issue", "view", str(job["issue"]), "--json", "labels"))
    for item in data["queue"]:
        worker = data["workers"].get(item["slot"])
        if worker and (not worker.get("job") or worker["job"].get("reconciled")):
            try:
                candidate_services = services(issue(item["issue"]))
                if candidate_services & active_services:
                    remaining.append(item)
                    continue
                assign_new(item["slot"], item["issue"], item["slug"])
                active_services |= candidate_services
                continue
            except (ValueError, RuntimeError) as exc:
                print(f"{item['slot']} #{item['issue']} 배정 보류: {exc}", file=sys.stderr)
        remaining.append(item)
    fresh = read_state()
    fresh["queue"] = remaining
    write_state(fresh)


def merged_pr(job: dict, prs: list[dict]) -> dict | None:
    return next((p for p in prs if p["baseRefName"] == "develop"
                 and p["headRefName"] == job["branch"]
                 and str(job["issue"]) in ISSUE_LINK_RE.findall(p["body"] or "")), None)


def continued_branch(job: dict) -> str | None:
    path = Path(job["worktree"]).resolve()
    current = next((name for name, worktree in shared.worktree_map().items()
                    if worktree == path), None)
    if not current or current == job["branch"]:
        return None
    match = BRANCH_RE.fullmatch(current)
    if not match or int(match.group(1)) != job["issue"]:
        raise ValueError("머지 뒤 워크트리가 다른 이슈 브랜치로 전환됐습니다")
    return current


def remove_merged_branch(branch: str) -> None:
    if shared.run("git", "show-ref", "--verify", f"refs/heads/{branch}", check=False) == "":
        return
    cherry = run("git", "cherry", "origin/develop", branch)
    if any(line.startswith("+ ") for line in cherry.splitlines()):
        raise ValueError("이전 브랜치에 develop 미반영 커밋이 있습니다")
    run("git", "branch", "-D", branch)


def failed_checks(pr: dict) -> list[str]:
    failed = {"FAILURE", "TIMED_OUT", "ACTION_REQUIRED", "STARTUP_FAILURE"}
    return sorted({check["name"] for check in pr.get("statusCheckRollup") or []
                   if check.get("status") == "COMPLETED" and check.get("conclusion") in failed})


def check_ci() -> None:
    data = read_state()
    prs = shared.gh_json("pr", "list", "--state", "open", "--limit", "500", "--json",
                         "number,headRefName,baseRefName,headRefOid,statusCheckRollup")
    for slot, worker in data["workers"].items():
        job = worker.get("job")
        if not job or job.get("reconciled"):
            continue
        pr = next((p for p in prs if p["headRefName"] == job["branch"]
                   and p["baseRefName"] == "develop"), None)
        if not pr:
            continue
        failures = failed_checks(pr)
        if not failures:
            continue
        signature = f"{pr['number']}:{pr['headRefOid']}:{','.join(failures)}"
        if job.get("ci_alerted") == signature:
            continue
        names = ", ".join(failures)
        queue_message(worker["thread"], Path(job["worktree"]),
                      f"[관리 감독 CI 실패] 본인 PR #{pr['number']}의 {names} 검사가 실패했습니다. "
                      "실패 job 로그로 원인을 확인해 수정하고, 관련 모듈을 다시 검증해 같은 PR에 푸시하세요. "
                      "필수 CI가 모두 통과할 때까지 완료로 보고하지 말고 머지하지 마세요.")
        job["ci_alerted"] = signature
        write_state(data)
        print(f"{slot}: PR #{pr['number']} CI 실패를 담당 세션에 전달")


def reconcile_one(slot: str, worker: dict, data: dict, prs: list[dict]) -> None:
    job = worker.get("job")
    if not job or job.get("reconciled"):
        return
    pr = merged_pr(job, prs)
    if not pr:
        return
    issue_data = shared.gh_json("issue", "view", str(job["issue"]), "--json", "body,state")
    items = shared.completed_items(issue_data["body"], pr["body"] or "")
    if not items:
        print(f"{slot}: PR #{pr['number']} 머지됨, 완료 항목은 수동 확인 필요")
    updated = shared.checked_body(issue_data["body"], items)
    if updated != issue_data["body"]:
        body_file = STATE / f"issue-{job['issue']}-body.md"
        body_file.write_text(updated, encoding="utf-8", newline="\n")
        run("gh", "issue", "edit", str(job["issue"]), "--body-file", str(body_file))
        body_file.unlink()
    next_branch = continued_branch(job)
    if next_branch:
        remove_merged_branch(job["branch"])
        job["last_merged_pr"] = pr["number"]
        job["branch"] = next_branch
        write_state(data)
        print(f"{slot}: PR #{pr['number']} 반영, 같은 워크트리의 {next_branch} 후속 작업 보존")
        return
    if not job.get("cleaned"):
        path = Path(job["worktree"])
        if not path.exists() and job["branch"] not in shared.worktree_map():
            remove_merged_branch(job["branch"])
            job["cleaned"] = True
        else:
            try:
                shared.safe_cleanup(job)
                job["cleaned"] = True
            except RuntimeError:
                if path.exists() and job["branch"] not in shared.worktree_map():
                    remove_merged_branch(job["branch"])
                    job["cleanup_pending_path"] = str(path)
                    print(f"{slot}: Windows 폴더 삭제 실패, 고아 폴더 {path} 수동 정리 필요")
                else:
                    raise
        write_state(data)
    if items and not any(mark == " " for mark, _ in shared.ITEM_RE.findall(updated)):
        if issue_data["state"] == "OPEN":
            run("gh", "issue", "close", str(job["issue"]))
    job["reconciled"] = True
    job["merged_pr"] = pr["number"]
    write_state(data)
    print(f"{slot}: PR #{pr['number']} 반영, 이슈 #{job['issue']} {len(items)}항목 체크")


def reconcile() -> None:
    run("git", "fetch", "--prune", "origin")
    data = read_state()
    prs = shared.pr_list("merged")
    for slot, worker in data["workers"].items():
        try:
            reconcile_one(slot, worker, data, prs)
        except (RuntimeError, ValueError) as exc:
            print(f"{slot}: 머지 정리 보류: {exc}", file=sys.stderr)


def show() -> None:
    data = read_state()
    for slot in sorted(SLOTS):
        worker = data["workers"].get(slot)
        if not worker:
            print(f"{slot}: 미등록")
            continue
        job = worker.get("job")
        status = "대기" if not job or job.get("reconciled") else f"#{job['issue']} {job['branch']}"
        print(f"{slot}: {status} · {worker['thread']}")
    if data["queue"]:
        print("후속 대기열: " + ", ".join(f"{x['slot']}→#{x['issue']}" for x in data["queue"]))


def tick() -> None:
    reconcile()
    check_ci()
    dispatch_queue()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    add = sub.add_parser("register")
    add.add_argument("slot", choices=sorted(SLOTS))
    add.add_argument("thread")
    assign_parser = sub.add_parser("assign")
    assign_parser.add_argument("slot", choices=sorted(SLOTS))
    assign_parser.add_argument("issue", type=int)
    assign_parser.add_argument("branch")
    assign_parser.add_argument("--handoff")
    assign_parser.add_argument("--note", default="")
    tracked = sub.add_parser("track")
    tracked.add_argument("slot", choices=sorted(SLOTS))
    tracked.add_argument("issue", type=int)
    tracked.add_argument("branch")
    waiting = sub.add_parser("queue")
    waiting.add_argument("slot", choices=sorted(SLOTS))
    waiting.add_argument("issue", type=int)
    waiting.add_argument("slug")
    sub.add_parser("status")
    sub.add_parser("reconcile")
    sub.add_parser("tick")
    watch = sub.add_parser("watch")
    watch.add_argument("--interval", type=int, default=300)
    args = parser.parse_args()
    if args.command == "watch":
        if args.interval < 30:
            parser.error("interval은 30초 이상이어야 합니다")
        while True:
            try:
                with state_lock():
                    tick()
            except (ValueError, RuntimeError) as exc:
                print(f"감독 오류: {exc}", file=sys.stderr)
            time.sleep(args.interval)
    else:
        with nullcontext() if args.command == "status" else state_lock():
            if args.command == "register":
                register(args.slot, args.thread)
            elif args.command == "assign":
                assign(args.slot, args.issue, args.branch, args.handoff, args.note)
            elif args.command == "track":
                track(args.slot, args.issue, args.branch)
            elif args.command == "queue":
                enqueue(args.slot, args.issue, args.slug)
            elif args.command == "status":
                show()
            elif args.command == "reconcile":
                reconcile()
            else:
                tick()


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError) as exc:
        print(f"오류: {exc}", file=sys.stderr)
        raise SystemExit(1)
