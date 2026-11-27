"""Keyboard-driven supervisor inbox for a separate terminal."""

from datetime import datetime
import shutil
import sys
import unicodedata


PAGE_SIZE = 10


def terminal_text(value, multiline=False):
    # Keep controls in SQLite, but prevent a message from controlling the terminal.
    return "".join(
        char if (multiline and char == "\n") or unicodedata.category(char) not in ("Cc", "Cf")
        else ascii(char)[1:-1]
        for char in value
    )


def cells(char):
    if unicodedata.category(char) in ("Mn", "Me"):
        return 0
    return 2 if unicodedata.east_asian_width(char) in ("F", "W") else 1


def clip(text, width):
    if sum(map(cells, text)) <= width:
        return text
    if width <= 0:
        return ""
    used = 0
    for index, char in enumerate(text):
        used += cells(char)
        if used >= width:
            return text[:index] + "…"


def preview(row, width):
    body = next(line.strip() for line in row["body"].splitlines() if line.strip())
    if row["sender"] == "PR 감시" and body.startswith("[Commerce Foundry PR 감시] "):
        _, _, body = body.partition(". ")
        body = body or row["body"]
    read = "읽음" if row["read_at"] is not None else "새 글"
    action = " [후속]" if row["action_required"] and row["state"] != "completed" else ""
    work = terminal_text(row["work_key"] or row["task_key"] or "—")
    created = datetime.fromtimestamp(row["created"]).astimezone().strftime("%m-%d %H:%M %z")
    header = f"[{read}]{action} {created} | {row['sender']} | "
    available = width - sum(map(cells, header)) - 3
    work = clip(work, min(24, max(0, available // 3)))
    remaining = available - sum(map(cells, work))
    return clip(f"{header}{work} | {clip(terminal_text(body), remaining)}", width)


def view_inbox(team, role, thread, *, actions_only=False):
    if not sys.stdin.isatty() or not sys.stdout.isatty():
        raise ValueError("view는 대화형 터미널에서 실행하세요. JSON 조회는 inbox를 사용하세요")

    page = None
    while True:
        rows = team.inbox(role, thread, include_completed=True, actions_only=actions_only)
        unread = sum(row["read_at"] is None for row in rows)
        pages = max(1, (len(rows) + PAGE_SIZE - 1) // PAGE_SIZE)
        page = pages - 1 if page is None else min(page, pages - 1)
        visible = rows[page * PAGE_SIZE:(page + 1) * PAGE_SIZE]
        title = "감독 inbox · PR 후속" if actions_only else "감독 inbox"
        print(f"\n{title} | 읽지 않음 {unread}건 | {page + 1}/{pages}쪽")
        columns = shutil.get_terminal_size((100, 24)).columns
        for number, row in enumerate(visible, 1):
            print(f"{number:>2}. {preview(row, columns - 5)}")
        try:
            key = input("번호 원문 · n 다음 · p 이전 · Enter 새로고침 · q 종료 > ").strip().lower()
        except (EOFError, KeyboardInterrupt):
            return
        if key == "q":
            return
        if key == "n":
            page = min(page + 1, pages - 1)
        elif key == "p":
            page = max(0, page - 1)
        elif key in {str(index) for index in range(1, len(visible) + 1)}:
            row = team.open_message(role, thread, visible[int(key) - 1]["id"])
            print(f"\n{row['id']} | 처리 {row['state']} | 전달 {row['transport']}")
            print(terminal_text(row["body"], multiline=True))
            try:
                input("Enter 목록 > ")
            except (EOFError, KeyboardInterrupt):
                return
        elif key == "":
            page = None
        else:
            print("목록에 있는 번호나 이동 키를 입력하세요.")

