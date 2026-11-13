import json
from pathlib import Path
import sqlite3
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import patch

import codex_session_mail as mail


class SessionMailTests(unittest.TestCase):
    def test_discovers_blank_user_session_but_excludes_subagent_and_archived(self):
        with TemporaryDirectory() as temp:
            home = Path(temp)
            db = sqlite3.connect(home / "state_5.sqlite")
            db.execute("""CREATE TABLE threads (
                id TEXT, name TEXT, title TEXT, cwd TEXT, source TEXT,
                thread_source TEXT, created_at INTEGER, updated_at INTEGER,
                archived INTEGER, has_user_event INTEGER, tokens_used INTEGER,
                first_user_message TEXT)""")
            rows = [
                ("user-1", None, None, temp, "vscode", "user", 1, 3, 0, 0, 0, ""),
                ("legacy-1", None, "legacy", temp, "vscode", None, 1, 2, 0, 1, 10, "hello"),
                ("sub-1", None, None, temp, "subagent", "subagent", 1, 4, 0, 0, 0, ""),
                ("old-1", None, "old", temp, "cli", "user", 1, 5, 1, 1, 10, "old"),
            ]
            db.executemany("INSERT INTO threads VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", rows)
            db.commit()
            db.close()
            found = mail.sessions(home)
            self.assertEqual(["user-1", "legacy-1"], [row["id"] for row in found])
            self.assertEqual(0, found[0]["tokens_used"])

    def test_target_requires_unique_id_prefix_or_exact_name(self):
        rows = [
            {"id": "12345678-aaaa", "name": "worker", "title": "one"},
            {"id": "12345678-bbbb", "name": "worker", "title": "two"},
        ]
        self.assertEqual("12345678-aaaa", mail.resolve(rows, "12345678-a")["id"])
        with self.assertRaisesRegex(ValueError, "모호"):
            mail.resolve(rows, "worker")
        with self.assertRaisesRegex(ValueError, "모호"):
            mail.resolve(rows, "12345678")

    def test_empty_list_excludes_sessions_that_already_answered(self):
        rows = [
            {"id": "new", "tokens_used": 0, "first_user_message": "", "name": None,
             "title": None, "updated_at": 1, "source": "vscode", "cwd": "root"},
            {"id": "used", "tokens_used": 10, "first_user_message": "hello", "name": None,
             "title": None, "updated_at": 1, "source": "vscode", "cwd": "root"},
        ]
        with patch.object(mail, "pending", return_value={}), \
             patch.object(mail, "worker_labels", return_value={}), \
             patch.object(mail, "turn_statuses", return_value={}), \
             patch("builtins.print") as output:
            mail.show(rows, Path("."), 10, True, False)
        self.assertEqual(1, output.call_count)
        self.assertIn("new", output.call_args.args[0])

    def test_send_uses_selected_session_and_explicit_worktree(self):
        with TemporaryDirectory() as temp, \
             patch.object(mail.shutil, "which", return_value="codex.cmd"), \
             patch.object(mail.subprocess, "run") as run:
            run.return_value.returncode = 0
            run.return_value.stdout = "Queued message id"
            run.return_value.stderr = ""
            mail.send({"id": "session-1", "cwd": "missing"}, "hello", temp)
            args = run.call_args.args[0]
            self.assertEqual(["codex.cmd", "queue", "--thread", "session-1"], args[:4])
            self.assertEqual("hello", args[5])
            self.assertIn("--resume", args)
            self.assertEqual(temp, args[-1])

    def test_latest_turn_status_is_reported(self):
        with TemporaryDirectory() as temp:
            home = Path(temp)
            db = sqlite3.connect(home / "thread_history_1.sqlite")
            db.execute("CREATE TABLE thread_turns (thread_id TEXT, status TEXT, started_at INTEGER)")
            db.executemany("INSERT INTO thread_turns VALUES (?,?,?)", [
                ("session-1", "completed", 1), ("session-1", "inProgress", 2),
            ])
            db.commit()
            db.close()
            self.assertEqual({"session-1": "inProgress"},
                             mail.turn_statuses(home, ["session-1"]))

    def test_read_returns_latest_agent_reply(self):
        with TemporaryDirectory() as temp:
            home = Path(temp)
            db = sqlite3.connect(home / "thread_history_1.sqlite")
            db.execute("CREATE TABLE thread_items (thread_id TEXT, item_type TEXT, rollout_ordinal INTEGER, item_json TEXT)")
            db.execute("INSERT INTO thread_items VALUES (?,?,?,?)", (
                "session-1", "agentMessage", 1,
                json.dumps({"phase": "final_answer", "text": "수신 확인"}),
            ))
            db.commit()
            db.close()
            with patch("builtins.print") as output:
                mail.read(home, {"id": "session-1"}, 1)
            output.assert_called_once_with("[final_answer] 수신 확인")


if __name__ == "__main__":
    unittest.main()
