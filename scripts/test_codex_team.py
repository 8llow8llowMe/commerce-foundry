import concurrent.futures
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
import uuid

import codex_team as team


class TeamTests(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "team.sqlite"
        self.store = team.Team(self.path)
        self.addCleanup(self.store.close)
        self.supervisor, self.worker, self.other = [str(uuid.uuid4()) for _ in range(3)]
        self.store.join("supervisor", self.supervisor, self.temp.name)
        self.store.join("S1", self.worker, self.temp.name)

    def send(self, **kwargs):
        return self.store.send("supervisor", self.supervisor, "S1", "한국어 인계", **kwargs)

    def test_takeover_preserves_tasks_and_blocks_old_supervisor(self):
        mid = self.send(task_key="#582")
        with self.assertRaises(ValueError):
            self.store.join("supervisor", self.other, self.temp.name)
        self.store.join("supervisor", self.other, self.temp.name, takeover=True)
        with self.assertRaisesRegex(ValueError, "소유자"):
            self.send()
        self.assertEqual(mid, self.store.inbox("S1", self.worker)[0]["id"])
        self.assertEqual(2, self.store.member("supervisor")["generation"])

    def test_worker_takeover_reads_previous_pending_task(self):
        mid = self.send(task_key="#20")
        self.store.join("S1", self.other, self.temp.name, takeover=True)
        self.assertEqual(mid, self.store.inbox("S1", self.other)[0]["id"])
        with self.assertRaises(ValueError):
            self.store.ack("S1", self.worker, mid, "received")

    def test_same_task_cannot_be_assigned_to_two_workers(self):
        self.store.join("S2", self.other, self.temp.name)
        self.send(task_key="#20")
        with self.assertRaisesRegex(ValueError, "같은 작업"):
            self.store.send("supervisor", self.supervisor, "S2", "중복", "#20")
        with self.assertRaisesRegex(ValueError, "미완료"):
            self.send(task_key="#582")

    def test_concurrent_assignments_have_one_winner(self):
        self.store.join("S2", self.other, self.temp.name)

        def assign(role):
            store = team.Team(self.path)
            try:
                store.send("supervisor", self.supervisor, role, "경합", "#20")
                return True
            except ValueError:
                return False
            finally:
                store.close()

        with concurrent.futures.ThreadPoolExecutor(2) as pool:
            self.assertEqual(1, sum(pool.map(assign, ["S1", "S2"])))

    def test_receipt_requires_correct_owner_and_order(self):
        mid = self.send(task_key="#20")
        for state in ("started", "completed"):
            with self.assertRaises(ValueError):
                self.store.ack("S1", self.worker, mid, state)
        for state in team.STATES:
            self.store.ack("S1", self.worker, mid, state)
        self.assertEqual([], self.store.inbox("S1", self.worker))
        self.assertEqual("completed", self.store.inbox("S1", self.worker, True)[0]["state"])
        self.send(task_key="#20")

    def test_worker_reports_to_new_supervisor(self):
        self.store.join("supervisor", self.other, self.temp.name, takeover=True)
        mid = self.store.send("S1", self.worker, "supervisor", "진행 보고")
        self.assertEqual(mid, self.store.inbox("supervisor", self.other)[0]["id"])
        with self.assertRaises(ValueError):
            self.store.send("S1", self.worker, "S1", "배정", "#1")

    def test_restart_keeps_unicode_and_states(self):
        mid = self.send()
        self.store.ack("S1", self.worker, mid, "received")
        other = team.Team(self.path)
        try:
            row = other.inbox("S1", self.worker)[0]
            self.assertEqual("한국어 인계", row["body"])
            self.assertEqual("received", row["state"])
        finally:
            other.close()

    def test_live_foreground_cannot_be_overwritten(self):
        token, selected = self.store.reserve("S1", self.temp.name)
        self.assertEqual(self.worker, selected)
        with self.assertRaises(ValueError):
            self.store.join("S1", self.other, self.temp.name, takeover=True)
        self.assertFalse(self.store.pulse("S1", "wrong-token"))
        self.store.release("S1", "wrong-token")
        self.assertTrue(team.live(self.store.member("S1")))
        self.store.release("S1", token)
        self.assertFalse(team.live(self.store.member("S1")))

    def test_new_foreground_binds_only_with_valid_token(self):
        token, selected = self.store.reserve("S2", self.temp.name)
        self.assertIsNone(selected)
        self.store.join("S2", self.other, self.temp.name, token=token)
        self.assertEqual(self.other, self.store.member("S2")["thread"])
        with self.assertRaises(ValueError):
            self.store.join("S2", str(uuid.uuid4()), self.temp.name, token=token)
        self.store.release("S2", token)
        with self.assertRaises(ValueError):
            self.store.join("S2", self.other, self.temp.name, token=token)

    def test_delivery_never_resumes_background_and_is_not_receipt(self):
        mid = self.send()
        token, _ = self.store.reserve("S1", self.temp.name)
        with patch.object(team, "codex", return_value="codex.cmd"), \
             patch.object(team.subprocess, "run", return_value=Mock(returncode=0)) as run:
            self.store.deliver("S1", token)
            self.store.deliver("S1", token)
        self.assertEqual(1, run.call_count)
        self.assertNotIn("--resume", run.call_args.args[0])
        row = self.store.inbox("S1", self.worker)[0]
        self.assertEqual((mid, "queued", "pending"), (row["id"], row["transport"], row["state"]))

    def test_disconnected_worker_keeps_message_without_dispatch(self):
        self.send()
        token, _ = self.store.reserve("S1", self.temp.name)
        self.store.release("S1", token)
        with patch.object(team.subprocess, "run") as run:
            self.store.deliver("S1", token)
            run.assert_not_called()
        self.assertEqual("pending", self.store.inbox("S1", self.worker)[0]["transport"])

    def test_timeout_is_unknown_and_never_automatically_replayed(self):
        self.send()
        token, _ = self.store.reserve("S1", self.temp.name)
        with patch.object(team, "codex", return_value="codex"), \
             patch.object(team.subprocess, "run", side_effect=subprocess.TimeoutExpired("codex", 20)) as run:
            self.store.deliver("S1", token)
            self.store.deliver("S1", token)
        self.assertEqual(1, run.call_count)
        self.assertEqual("unknown", self.store.inbox("S1", self.worker)[0]["transport"])

    def test_stale_heartbeat_requires_foreground_reconnect(self):
        token, _ = self.store.reserve("S1", self.temp.name)
        self.store.db.execute("UPDATE members SET heartbeat=1 WHERE role='S1'")
        self.send()
        with patch.object(team.subprocess, "run") as run:
            self.store.deliver("S1", token)
            run.assert_not_called()

    def test_launch_requires_terminal(self):
        with patch.object(team.sys.stdin, "isatty", return_value=False):
            with self.assertRaisesRegex(ValueError, "대화형"):
                team.launch(self.store, "S1", Path(self.temp.name))

    def test_launch_inherits_terminal_and_releases_even_on_spawn_failure(self):
        with patch.object(team.sys.stdin, "isatty", return_value=True), \
             patch.object(team.sys.stdout, "isatty", return_value=True), \
             patch.object(team, "codex", return_value="codex"), \
             patch.object(team.subprocess, "Popen", side_effect=OSError("spawn failed")) as popen:
            with self.assertRaises(OSError):
                team.launch(self.store, "S1", Path(self.temp.name))
        self.assertFalse(team.live(self.store.member("S1")))
        self.assertNotIn("stdout", popen.call_args.kwargs)
        self.assertNotIn("stdin", popen.call_args.kwargs)
        self.assertNotIn("shell", popen.call_args.kwargs)
        self.assertEqual(["codex", "--no-daemon", "resume", self.worker], popen.call_args.args[0][:4])

    def test_release_runs_even_when_child_shutdown_fails(self):
        child = Mock()
        child.poll.return_value = 0
        with patch.object(team.sys.stdin, "isatty", return_value=True), \
             patch.object(team.sys.stdout, "isatty", return_value=True), \
             patch.object(team, "codex", return_value="codex"), \
             patch.object(team.subprocess, "Popen", return_value=child), \
             patch.object(team, "stop_child", side_effect=RuntimeError("종료 미확인")):
            with self.assertRaisesRegex(RuntimeError, "종료 미확인"):
                team.launch(self.store, "S1", Path(self.temp.name))
        self.assertFalse(team.live(self.store.member("S1")))

    def test_join_from_other_worktree_uses_actual_cwd(self):
        with patch.object(team, "state_path", return_value=self.path), \
             patch.dict(os.environ, {"CODEX_THREAD_ID": self.worker}, clear=True), \
             patch.object(team.Path, "cwd", return_value=Path(self.temp.name)), \
             patch.object(team.sys, "argv", ["codex_team.py", "join", "S1"]):
            team.main()
        self.assertEqual(self.temp.name, self.store.member("S1")["cwd"])

    @unittest.skipUnless(os.name == "nt", "Windows 전경 프로세스 트리 검증")
    def test_real_windows_process_tree_is_stopped(self):
        import ctypes
        import sys
        script = ("import subprocess,sys,time; "
                  "p=subprocess.Popen([sys.executable,'-c','import time;time.sleep(60)']); "
                  "print(p.pid,flush=True);time.sleep(60)")
        child = subprocess.Popen([sys.executable, "-u", "-c", script], stdout=subprocess.PIPE,
                                 text=True)
        self.addCleanup(child.stdout.close)
        try:
            descendant = int(child.stdout.readline())
            team.stop_child(child)
            self.assertIsNotNone(child.poll())
            kernel = ctypes.WinDLL("kernel32", use_last_error=True)
            kernel.OpenProcess.restype = ctypes.c_void_p
            handle = kernel.OpenProcess(0x1000, False, descendant)
            if handle:
                try:
                    code = ctypes.c_ulong()
                    self.assertTrue(kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code)))
                    self.assertNotEqual(259, code.value)
                finally:
                    kernel.CloseHandle(ctypes.c_void_p(handle))
        finally:
            if child.poll() is None:
                team.stop_child(child)

    def test_messages_reject_missing_identity_and_empty_content(self):
        with self.assertRaises(ValueError):
            self.store.send("supervisor", self.other, "S1", "잘못된 발신자")
        for content in ("", "가" * 11000):
            with self.assertRaises(ValueError):
                self.store.send("supervisor", self.supervisor, "S1", content)


if __name__ == "__main__":
    unittest.main()
