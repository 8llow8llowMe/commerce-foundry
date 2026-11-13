#!/usr/bin/env python3
"""Find local independent Codex sessions and exchange queued messages."""

from __future__ import annotations

import argparse
from contextlib import closing
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import sys


def codex_home() -> Path:
    return Path(os.environ.get("CODEX_HOME", Path.home() / ".codex"))


def database(home: Path, prefix: str) -> Path:
    candidates = sorted(
        home.glob(f"{prefix}_*.sqlite"),
        key=lambda path: int(path.stem.removeprefix(f"{prefix}_"))
        if path.stem.removeprefix(f"{prefix}_").isdigit() else -1,
        reverse=True,
    )
    if not candidates:
        raise ValueError(f"Codex {prefix} DB를 찾을 수 없습니다: {home}")
    return candidates[0]


def connect(path: Path) -> sqlite3.Connection:
    return sqlite3.connect(f"file:{path.resolve().as_posix()}?mode=ro", uri=True)


def sessions(home: Path) -> list[dict]:
    with closing(connect(database(home, "state"))) as db:
        db.row_factory = sqlite3.Row
        try:
            rows = db.execute("""
                SELECT id, name, title, cwd, source, thread_source,
                       created_at, updated_at, archived, has_user_event,
                       tokens_used, first_user_message
                FROM threads
                WHERE (thread_source = 'user' OR
                       (thread_source IS NULL AND source IN ('cli', 'vscode', 'exec')))
                  AND archived = 0
                ORDER BY updated_at DESC
            """).fetchall()
        except sqlite3.Error as exc:
            raise ValueError(f"Codex 세션 DB 형식이 예상과 다릅니다: {exc}") from exc
    return [dict(row) for row in rows]


def resolve(rows: list[dict], target: str) -> dict:
    exact = [row for row in rows if row["id"].lower() == target.lower()]
    if exact:
        return exact[0]
    if len(target) >= 8 and re.fullmatch(r"[0-9a-f-]+", target, re.I):
        matches = [row for row in rows if row["id"].lower().startswith(target.lower())]
    else:
        matches = [row for row in rows if target == row["name"] or target == row["title"]]
    if len(matches) == 1:
        return matches[0]
    if not matches:
        raise ValueError(f"세션을 찾을 수 없습니다: {target}")
    ids = ", ".join(row["id"] for row in matches)
    raise ValueError(f"대상이 모호합니다. 전체 ID를 지정하세요: {ids}")


def pending(home: Path) -> dict[str, int]:
    try:
        path = database(home, "queue")
    except ValueError:
        return {}
    with closing(connect(path)) as db:
        try:
            return dict(db.execute(
                "SELECT thread_id, COUNT(*) FROM queued_items GROUP BY thread_id"
            ).fetchall())
        except sqlite3.Error as exc:
            raise ValueError(f"Codex 큐 DB 형식이 예상과 다릅니다: {exc}") from exc


def turn_statuses(home: Path, ids: list[str]) -> dict[str, str]:
    if not ids:
        return {}
    placeholders = ",".join("?" for _ in ids)
    with closing(connect(database(home, "thread_history"))) as db:
        try:
            rows = db.execute(f"""
                SELECT thread_id, status FROM thread_turns
                WHERE thread_id IN ({placeholders})
                ORDER BY started_at DESC
            """, ids).fetchall()
        except sqlite3.Error as exc:
            raise ValueError(f"Codex 대화 DB 형식이 예상과 다릅니다: {exc}") from exc
    result = {}
    for thread_id, status in rows:
        result.setdefault(thread_id, status)
    return result


def worker_labels() -> dict[str, str]:
    root = Path(__file__).resolve().parent
    git = subprocess.run(
        ["git", "-C", str(root), "rev-parse", "--path-format=absolute", "--git-common-dir"],
        text=True, encoding="utf-8", capture_output=True,
    )
    if git.returncode:
        return {}
    common = Path(git.stdout.strip()).resolve()
    repo = common.parent if common.name == ".git" else root.parent
    path = repo.parent / ".commerce-foundry-be-queue" / "workers.json"
    if not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {
        worker["thread"]: f"{slot}/#{worker['job']['issue']}" if worker.get("job") else slot
        for slot, worker in data.get("workers", {}).items()
    }


def timestamp(seconds: int) -> str:
    return datetime.fromtimestamp(seconds, timezone.utc).astimezone().strftime("%Y-%m-%d %H:%M")


def show(rows: list[dict], home: Path, limit: int, empty: bool, json_output: bool) -> None:
    if empty:
        rows = [row for row in rows if row["tokens_used"] == 0
                and not row["first_user_message"]]
    rows = rows[:limit]
    counts = pending(home)
    labels = worker_labels()
    statuses = turn_statuses(home, [row["id"] for row in rows])
    for row in rows:
        row["pending"] = counts.get(row["id"], 0)
        row["worker"] = labels.get(row["id"])
        row["turn_status"] = statuses.get(row["id"], "not_started")
        row.pop("first_user_message", None)
    if json_output:
        print(json.dumps(rows, ensure_ascii=False, indent=2))
        return
    for row in rows:
        label = row["name"] or row["title"] or "(빈 세션)"
        worker = f" {row['worker']}" if row["worker"] else ""
        print(f"{row['id']} {timestamp(row['updated_at'])} {row['source']}{worker} "
              f"대기={row['pending']} 턴={row['turn_status']} {label}\n  {row['cwd']}")


def send(row: dict, message: str, cwd: str | None) -> None:
    workdir = Path((cwd or row["cwd"]).removeprefix("\\\\?\\"))
    if not workdir.is_dir():
        raise ValueError(f"작업 경로가 없습니다: {workdir}")
    executable = shutil.which("codex.cmd" if os.name == "nt" else "codex")
    if not executable:
        raise ValueError("Codex CLI를 찾을 수 없습니다")
    result = subprocess.run(
        [executable, "queue", "--thread", row["id"], "--message", message,
         "--resume", "-C", str(workdir)],
        cwd=workdir, text=True, encoding="utf-8", capture_output=True,
    )
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or result.stdout.strip())
    print(result.stdout.strip())
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    print("큐 등록 완료. 화면 수신·답변은 read 명령으로 확인하세요.")


def read(home: Path, row: dict, limit: int) -> None:
    with closing(connect(database(home, "thread_history"))) as db:
        try:
            items = db.execute("""
                SELECT item_json FROM thread_items
                WHERE thread_id = ? AND item_type = 'agentMessage'
                ORDER BY rollout_ordinal DESC LIMIT ?
            """, (row["id"], limit)).fetchall()
        except sqlite3.Error as exc:
            raise ValueError(f"Codex 대화 DB 형식이 예상과 다릅니다: {exc}") from exc
    if not items:
        print("아직 답변이 없습니다.")
    for (raw,) in reversed(items):
        item = json.loads(raw)
        print(f"[{item.get('phase', 'message')}] {item.get('text', '')}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    listing = sub.add_parser("list")
    listing.add_argument("--limit", type=int, default=30)
    listing.add_argument("--empty", action="store_true")
    listing.add_argument("--json", action="store_true")
    sending = sub.add_parser("send")
    sending.add_argument("target")
    message = sending.add_mutually_exclusive_group(required=True)
    message.add_argument("--message")
    message.add_argument("--message-file", type=Path)
    sending.add_argument("--cwd")
    reading = sub.add_parser("read")
    reading.add_argument("target")
    reading.add_argument("--limit", type=int, default=3)
    args = parser.parse_args()
    home = codex_home()
    rows = sessions(home)
    if args.command == "list":
        if args.limit < 1:
            parser.error("--limit은 1 이상이어야 합니다")
        show(rows, home, args.limit, args.empty, args.json)
    elif args.command == "send":
        body = args.message if args.message is not None else args.message_file.read_text(encoding="utf-8")
        if not body.strip():
            raise ValueError("빈 메시지는 보낼 수 없습니다")
        send(resolve(rows, args.target), body, args.cwd)
    else:
        if args.limit < 1:
            parser.error("--limit은 1 이상이어야 합니다")
        read(home, resolve(rows, args.target), args.limit)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, json.JSONDecodeError) as exc:
        print(f"오류: {exc}", file=sys.stderr)
        raise SystemExit(1)
