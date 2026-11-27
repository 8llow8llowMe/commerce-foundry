from contextlib import closing, redirect_stdout
from io import StringIO
import os
from pathlib import Path
import sqlite3
import subprocess
import sys
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
import uuid

import codex_pr_watch as watch
import codex_team
from codex_team_inbox import cells, preview, view_inbox


class SupervisorInboxTests(unittest.TestCase):
    def setUp(self):
        temp = TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name) / "team.sqlite"
        self.team = codex_team.Team(self.path)
        self.addCleanup(self.team.close)
        self.owner, self.worker, self.second = (str(uuid.uuid4()) for _ in range(3))
        for role, thread in (("supervisor", self.owner), ("S1", self.worker), ("S2", self.second)):
            self.team.join(role, thread, temp.name)

    def report(self, body, **kwargs):
        return self.team.send("S1", self.worker, "supervisor", body, **kwargs)

    def test_new_and_legacy_reports_never_enter_foreground_or_live(self):
        token, _ = self.team.reserve("supervisor", self.path.parent)
        legacy = self.report("기존 보고")
        self.team.db.execute("UPDATE messages SET transport='pending' WHERE id=?", (legacy,))
        report = self.report("새 보고")
        client = Mock()
        with patch.object(codex_team.subprocess, "run") as run:
            for _ in range(2):
                self.team.deliver("supervisor", token)
            run.assert_not_called()
        for mid in (legacy, report):
            with self.assertRaises(ValueError):
                self.team.deliver_live(mid, client)
        client.target.assert_not_called()
        rows = self.team.inbox("supervisor", self.owner)
        self.assertEqual([(legacy, "pending"), (report, "held")],
                         [(row["id"], row["transport"]) for row in rows])

    def test_display_opens_original_and_read_is_independent_of_ack(self):
        self.team.send("supervisor", self.owner, "S1", "배정", "#777")
        first = self.report("첫 줄\n두 번째 줄\n\x1b[2J콘솔 이스케이프")
        second = self.team.send("S2", self.second, "supervisor", "다른 보고", work_key="#888")
        out = StringIO()
        with patch.object(sys.stdin, "isatty", return_value=True), \
             patch.object(out, "isatty", return_value=True), \
             patch("builtins.input", side_effect=["1", "", "q"]), redirect_stdout(out):
            view_inbox(self.team, "supervisor", self.owner)
        display = out.getvalue()
        self.assertIn("읽지 않음 2건", display)
        self.assertIn("S1 | #777 | 첫 줄", display)
        self.assertIn("S2 | #888 | 다른 보고", display)
        self.assertIn("두 번째 줄\n\\x1b[2J콘솔 이스케이프", display)
        self.assertIn("읽지 않음 1건", display)
        self.team.join("supervisor", str(uuid.uuid4()), self.path.parent, takeover=True)
        with self.assertRaisesRegex(ValueError, "소유자"):
            self.team.open_message("supervisor", self.owner, second)
        with closing(codex_team.Team(self.path)) as reopened:
            owner = reopened.member("supervisor")["thread"]
            rows = reopened.inbox("supervisor", owner)
            self.assertEqual([first, second], [row["id"] for row in rows])
            self.assertIsNotNone(rows[0]["read_at"])
            self.assertEqual("pending", rows[0]["state"])
            self.assertIsNone(rows[1]["read_at"])
            self.assertEqual("첫 줄\n두 번째 줄\n\x1b[2J콘솔 이스케이프", rows[0]["body"])

    def test_preview_fits_an_80_column_terminal_with_wide_text(self):
        self.report("미리보기" * 70, work_key="#777/" + "업무" * 100)
        row, = self.team.inbox("supervisor", self.owner)
        line = preview(row, 75)
        self.assertLessEqual(sum(map(cells, line)), 75)
        self.assertIn("S1 | #777/", line)
        self.assertIn("미리보기", line)

    def test_same_event_keeps_original_and_is_scoped_to_sender(self):
        first = self.report("처음", event_key="777/started")
        self.assertEqual(first, self.report("다시 보고", event_key="777/started"))
        self.team.send("S2", self.second, "supervisor", "다른 보낸 사람", event_key="777/started")
        self.assertEqual(["처음", "다른 보낸 사람"],
                         [row["body"] for row in self.team.inbox("supervisor", self.owner)])

    def test_urgent_requires_event_key_and_cli_does_not_redeliver(self):
        with self.assertRaisesRegex(ValueError, "--event-key"):
            self.report("결정 요청", live_delivery=True)
        args = ["codex_team.py", "send", "supervisor", "--from-role", "S1", "--message",
                "결정 요청", "--event-key", "777/blocked", "--live"]
        client = Mock()
        client.target.return_value = {"cwd": str(codex_team.ROOT)}
        client.push.return_value = "turn"
        with patch.object(sys, "argv", args), \
             patch.dict(os.environ, {"CODEX_TEAM_DB": str(self.path), "CODEX_THREAD_ID": self.worker}), \
             patch.object(codex_team, "codex", return_value="codex"), \
             patch("codex_live.Client", return_value=client) as connect, redirect_stdout(StringIO()):
            self.assertEqual(0, codex_team.main())
            self.assertEqual(0, codex_team.main())
        connect.assert_called_once_with("codex")
        client.push.assert_called_once()
        self.assertIn("사용자 승인이나 권한 확대가 아닙니다", client.push.call_args.args[1])
        self.assertIn("일반 보고는 inbox", client.push.call_args.args[1])
        row, = self.team.inbox("supervisor", self.owner)
        self.assertEqual(("live_accepted", "pending", None),
                         (row["transport"], row["state"], row["read_at"]))

    def test_parallel_cli_sessions_share_storage_and_assignment_ack(self):
        script = Path(codex_team.__file__).resolve()

        def process(thread, *args):
            env = dict(os.environ, CODEX_TEAM_DB=str(self.path), CODEX_THREAD_ID=thread)
            return subprocess.Popen([sys.executable, "-B", "-X", "utf8", str(script), *args],
                                    env=env, text=True, encoding="utf-8", stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE)

        commands = (
            (self.worker, "send", "supervisor", "--from-role", "S1", "--message", "첫 보고",
             "--event-key", "같은-이벤트"),
            (self.worker, "send", "supervisor", "--from-role", "S1", "--message", "첫 보고",
             "--event-key", "같은-이벤트"),
            (self.second, "send", "supervisor", "--from-role", "S2", "--message", "둘째 보고"),
        )
        children = [process(*command) for command in commands]
        for child in children:
            self.addCleanup(child.wait)
            self.addCleanup(child.kill)
            stdout, stderr = child.communicate(timeout=25)
            self.assertEqual(child.returncode, 0, (stdout, stderr))
        rows = self.team.inbox("supervisor", self.owner)
        self.assertCountEqual(["첫 보고", "둘째 보고"], [row["body"] for row in rows])

        assignment = self.team.send("supervisor", self.owner, "S1", "새 배정", "#777")
        for state in codex_team.STATES:
            with process(self.worker, "ack", "S1", assignment, state) as child:
                stdout, stderr = child.communicate(timeout=25)
                self.assertEqual(child.returncode, 0, (stdout, stderr))
        row = self.team.inbox("S1", self.worker, include_completed=True)[0]
        self.assertEqual(("completed", None), (row["state"], row["read_at"]))

    def test_watcher_deduplicates_preserves_worker_actions_and_adopts_old_notification(self):
        config = {"since": "2026-11-27T00:00:00Z", "branches": {
            "feature/be/777-test": {"role": "S1", "issue": 777},
            "feature/be/888-test": {"role": "S2", "issue": 888},
        }}
        pr = {"number": 14, "state": "OPEN", "headRefName": "feature/be/777-test",
              "headRefOid": "head-1", "headRepositoryOwner": {"login": "8llow8llowme"},
              "isCrossRepository": False, "isDraft": False, "createdAt": "2026-11-27T04:00:00Z",
              "url": "https://example.invalid/pr/14", "mergeCommit": None}
        merged = dict(pr, number=15, state="MERGED", headRefName="feature/be/888-test",
                      mergeCommit={"oid": "merge-1"})
        details = {"headRefOid": "head-1", "mergeable": "CONFLICTING", "mergeStateStatus": "DIRTY",
                   "statusCheckRollup": [{"name": "backend-ci", "conclusion": "FAILURE"}]}
        listed = [pr, merged, dict(pr, number=16, isCrossRepository=True),
                  dict(pr, number=17, createdAt="2026-11-26T01:00:00Z"),
                  dict(pr, number=18, isDraft=True)]

        def fetch(command, *args):
            if command == "list":
                self.assertIn("develop", args)
                return listed
            self.assertEqual("14", args[0])
            return details

        previous = "[Commerce Foundry PR 감시] S2 PR #15 · 이슈 #888 · 기존 머지 후속 안내"
        old_merge = self.team.send("supervisor", self.owner, "supervisor", previous)
        outdated = self.team.send("supervisor", self.owner, "supervisor",
                                  "[Commerce Foundry PR 감시] S1 PR #14 · 이슈 #777 · 이전 HEAD CI 실패")
        baseline = {"merged:15:merge-1": {"message": old_merge, "state": "accepted"},
                    "failed:14:earlier:check": {"message": outdated, "state": "pending"}}
        self.assertEqual((2, 3), watch.record_prs(self.team, config, baseline, fetch))
        self.assertEqual((2, 0), watch.record_prs(self.team, config, baseline, fetch))
        actions = self.team.inbox("supervisor", self.owner, actions_only=True)
        self.assertEqual(old_merge, actions[0]["id"])
        self.assertEqual(previous, actions[0]["body"])
        self.assertEqual("#888 / PR #15", actions[0]["work_key"])
        self.assertEqual(outdated, actions[1]["id"])
        self.assertEqual("PR 감시", actions[1]["sender"])
        self.assertIn("담당 세션에 충돌 해결", actions[2]["body"])
        self.assertIn("backend-ci", actions[3]["body"])
        self.assertEqual((2, 0), watch.record_prs(self.team, config, {}, fetch))
        for row in actions:
            self.team.open_message("supervisor", self.owner, row["id"])
        self.assertEqual(4, len(self.team.inbox("supervisor", self.owner, actions_only=True)))
        for state in codex_team.STATES:
            self.team.ack("supervisor", self.owner, old_merge, state)
        self.assertEqual(3, len(self.team.inbox("supervisor", self.owner, actions_only=True)))
        pr["headRefOid"] = "head-2"
        self.assertEqual((2, 0), watch.record_prs(self.team, config, baseline, fetch))
        details.update(headRefOid="head-2", mergeable="MERGEABLE", mergeStateStatus="CLEAN",
                       statusCheckRollup=[{"status": "COMPLETED", "conclusion": "SUCCESS"}])
        self.assertEqual((2, 1), watch.record_prs(self.team, config, baseline, fetch))
        self.assertTrue(self.team.inbox("supervisor", self.owner)[-1]["body"].startswith("CI 완료"))

    def test_legacy_schema_migrates_without_losing_original(self):
        path = self.path.with_name("legacy.sqlite")
        with closing(sqlite3.connect(path)) as connection:
            connection.executescript("""
                CREATE TABLE messages (
                    id TEXT PRIMARY KEY, sender TEXT NOT NULL, recipient TEXT NOT NULL,
                    body TEXT NOT NULL, task_key TEXT, state TEXT NOT NULL DEFAULT 'pending',
                    transport TEXT NOT NULL DEFAULT 'pending', error TEXT,
                    created REAL NOT NULL, updated REAL NOT NULL);
                INSERT INTO messages VALUES ('old','S1','supervisor','이전 원문',NULL,
                    'received','live_accepted',NULL,1,2);
            """)
        with closing(codex_team.Team(path)) as migrated:
            migrated.join("supervisor", self.owner, self.path.parent)
            row, = migrated.inbox("supervisor", self.owner)
            self.assertEqual(("이전 원문", "received", "live_accepted", None),
                             (row["body"], row["state"], row["transport"], row["read_at"]))
