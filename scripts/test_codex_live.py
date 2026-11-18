import io
import json
import os
from pathlib import Path
import queue
import struct
import threading
import time
from tempfile import TemporaryDirectory
import unittest
from unittest.mock import Mock, patch
import uuid

import codex_live as live
import codex_team as team


def frame(opcode, payload=b"", fin=True):
    header = bytes([(128 if fin else 0) | opcode])
    size = len(payload)
    if size < 126:
        return header + bytes([size]) + payload
    return header + bytes([126]) + struct.pack("!H", size) + payload


class WireTests(unittest.TestCase):
    def setUp(self):
        self.client = live.Client.__new__(live.Client)
        self.client.buffer = b""
        self.client.timeout = .1
        self.client.counter = 0
        self.client.chunks = queue.Queue()
        self.client.process = Mock(stdin=io.BytesIO())

    def receive(self, data):
        self.client.buffer = data
        return self.client.receive(time.monotonic() + 1)

    def test_split_chunks_fragmentation_and_ping_pong(self):
        data = frame(1, b'{"a":', False) + frame(9, b"ping") + frame(0, b'1}')
        for value in data:
            self.client.chunks.put(bytes([value]))
        self.assertEqual({"a": 1}, self.receive(b""))
        self.assertEqual(138, self.client.process.stdin.getvalue()[0])

    def test_masked_outbound_frames_all_length_forms(self):
        for size in (5, 200, 65536):
            self.client.process.stdin = io.BytesIO()
            self.client._frame(1, b"x" * size)
            data = self.client.process.stdin.getvalue()
            offset = 2 if size < 126 else 4 if size < 65536 else 10
            self.assertTrue(data[1] & 128)
            mask, payload = data[offset:offset+4], data[offset+4:]
            self.assertEqual(b"x" * size, bytes(v ^ mask[i % 4] for i, v in enumerate(payload)))

    def test_rejects_oversize_reserved_and_invalid_control_frames(self):
        cases = [bytes([129, 127]) + struct.pack("!Q", live.MAX_BYTES + 1),
                 frame(0, b'{}'), frame(9, b"x", False), bytes([193, 0]),
                 bytes([129, 128]), frame(8)]
        for data in cases:
            with self.subTest(data=data[:4]), self.assertRaises(live.LiveError):
                self.receive(data)

    def test_no_data_and_closed_stream_fail_bounded(self):
        with self.assertRaisesRegex(live.LiveError, "시간 초과"):
            self.client._take(1, time.monotonic() - 1)
        self.client.chunks.put(b"")
        with self.assertRaisesRegex(live.LiveError, "종료"):
            self.client._take(1, time.monotonic() + 1)

    def test_stalled_pipe_write_terminates_only_its_proxy(self):
        release = threading.Event()
        stream = Mock()
        stream.write.side_effect = lambda data: release.wait(2)
        self.client.process.stdin = stream
        self.client.process.poll.return_value = None
        self.client.process.terminate.side_effect = release.set
        start = time.monotonic()
        with self.assertRaisesRegex(live.LiveError, "전송 시간 초과"):
            self.client._write(b"x" * 65536)
        self.assertLess(time.monotonic() - start, 1)
        self.client.process.terminate.assert_called_once()
        self.client.process.wait.assert_called_once_with(timeout=3)

    def test_rpc_ignores_notifications_and_other_request_ids(self):
        self.client.buffer = (frame(1, b'{"method":"item/event"}') +
                              frame(1, b'{"id":0,"result":{}}') +
                              frame(1, b'{"id":1,"result":{"ok":true}}'))
        self.assertEqual({"ok": True}, self.client.call("read", {}))

    def test_rpc_errors_and_malformed_response_are_not_retried(self):
        for response in (b'{"id":1,"error":{"message":"private details"}}', b'{"id":1}'):
            self.client.counter = 0
            self.client.buffer = frame(1, response)
            with self.assertRaises(live.LiveError) as caught:
                self.client.call("test", {})
            self.assertNotIn("private details", str(caught.exception))

    def test_handshake_failure_closes_proxy(self):
        child = Mock()
        child.stdout.read1.return_value = b"HTTP/1.1 403 Forbidden\r\n\r\n"
        with patch.object(live.subprocess, "Popen", return_value=child), \
             patch.object(live.Client, "_read"), \
             patch.object(live.Client, "_chunk", return_value=b"HTTP/1.1 403 Forbidden\r\n\r\n"), \
             patch.object(live.Client, "close") as close:
            with self.assertRaises(live.LiveError):
                live.Client("codex.exe")
            close.assert_called_once()


class RoutingTests(unittest.TestCase):
    def setUp(self):
        self.client = live.Client.__new__(live.Client)

    def test_not_loaded_never_reads_or_resumes(self):
        self.client.loaded = Mock(return_value=[])
        self.client.call = Mock()
        with self.assertRaisesRegex(live.LiveError, "로드되지"):
            self.client.target("old")
        self.client.call.assert_not_called()

    def test_ephemeral_and_subagent_are_not_independent_targets(self):
        for field in ({"ephemeral": True}, {"source": {"subAgent": {}}}, {"source": "subAgent"}):
            self.client.loaded = Mock(return_value=["id"])
            self.client.call = Mock(return_value={"thread": field})
            with self.assertRaisesRegex(live.LiveError, "독립"):
                self.client.target("id")
            self.assertFalse(live.independent(field))

    def test_idle_starts_without_policy_or_cwd_override(self):
        self.client.target = Mock(return_value={"status": {"type": "idle"}})
        self.client.call = Mock(return_value={"turn": {"id": "new"}})
        self.assertEqual("new", self.client.push("id", "hello"))
        self.client.call.assert_called_once_with("turn/start", {
            "threadId": "id", "input": [{"type": "text", "text": "hello"}]})

    def test_active_steers_exact_turn_and_ambiguous_state_fails(self):
        self.client.target = Mock(return_value={"status": {"type": "active"},
            "turns": [{"id": "active", "status": "inProgress"}]})
        self.client.call = Mock(side_effect=[{"data": self.client.target.return_value["turns"]}, {"turnId": "active"}])
        self.assertEqual("active", self.client.push("id", "hello"))
        self.assertEqual("active", self.client.call.call_args.args[1]["expectedTurnId"])
        self.client.target.return_value["turns"] = []
        self.client.call = Mock(return_value={"data": []})
        with self.assertRaises(live.LiveError):
            self.client.push("id", "hello")
        self.client.call.assert_called_once_with("thread/turns/list", {
            "threadId": "id", "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})

    def test_different_repository_fails_closed(self):
        with patch.object(live.subprocess, "run", side_effect=[
                Mock(returncode=0, stdout="/repo-a/.git"), Mock(returncode=0, stdout="/repo-b/.git")]):
            with self.assertRaisesRegex(live.LiveError, "다른 저장소"):
                live.same_repo("a", "b")

    def test_peers_filters_subagent_and_ephemeral_metadata(self):
        with TemporaryDirectory() as tmp:
            client = Mock()
            client.loaded.return_value = ["user", "sub", "ephemeral"]
            client.call.side_effect = [{"thread": row} for row in (
                {"id": "user", "cwd": tmp, "source": "vscode"},
                {"id": "sub", "cwd": tmp, "source": {"subAgent": {}}},
                {"id": "ephemeral", "cwd": tmp, "ephemeral": True})]
            with patch.object(team, "state_path", return_value=Path(tmp)/"state.sqlite"), \
                 patch.object(live, "Client", return_value=client), \
                 patch.object(live, "same_repo"), \
                 patch.object(team, "codex", return_value="codex"), \
                 patch.object(team.sys, "argv", ["codex_team.py", "peers"]), \
                 patch("builtins.print") as output:
                team.main()
            rows = json.loads(output.call_args.args[0])
            self.assertEqual(["user"], [r["id"] for r in rows])
            self.assertTrue(all(not call.args[1]["includeTurns"] for call in client.call.call_args_list))

    def test_non_supervisor_cannot_bind_loaded_thread(self):
        with TemporaryDirectory() as tmp:
            with patch.object(team, "state_path", return_value=Path(tmp)/"state.sqlite"), \
                 patch.object(live, "Client") as client, \
                 patch.object(team, "codex", return_value="codex"), \
                 patch.dict(os.environ, {"CODEX_THREAD_ID": str(uuid.uuid4())}), \
                 patch.object(team.sys, "argv", ["codex_team.py", "bind", "S1", "--thread", str(uuid.uuid4())]):
                with self.assertRaises(ValueError):
                    team.main()
                client.return_value.target.assert_not_called()

    def test_delivery_acceptance_is_not_receipt_and_no_duplicate_dispatch(self):
        with TemporaryDirectory() as tmp:
            store = team.Team(Path(tmp) / "team.sqlite")
            try:
                owner, worker = str(uuid.uuid4()), str(uuid.uuid4())
                store.join("supervisor", owner, tmp)
                store.join("S1", worker, tmp)
                mid = store.send("supervisor", owner, "S1", "한국어", "test")
                client = Mock()
                client.target.return_value = {"cwd": tmp}
                client.push.return_value = "turn"
                with patch.object(live, "same_repo"):
                    self.assertEqual("turn", store.deliver_live(mid, client))
                    with self.assertRaises(ValueError):
                        store.deliver_live(mid, client)
                self.assertEqual(1, client.push.call_count)
                row = store.inbox("S1", worker)[0]
                self.assertEqual(("pending", "live_accepted"), (row["state"], row["transport"]))
            finally:
                store.close()

    def test_unknown_delivery_keeps_original_without_automatic_replay(self):
        with TemporaryDirectory() as tmp:
            store = team.Team(Path(tmp) / "team.sqlite")
            try:
                owner, worker = str(uuid.uuid4()), str(uuid.uuid4())
                store.join("supervisor", owner, tmp)
                store.join("S1", worker, tmp)
                mid = store.send("supervisor", owner, "S1", "인계")
                client = Mock()
                client.target.return_value = {"cwd": tmp}
                client.push.side_effect = live.LiveError("lost response")
                with patch.object(live, "same_repo"):
                    with self.assertRaises(live.LiveError):
                        store.deliver_live(mid, client)
                    with self.assertRaises(ValueError):
                        store.deliver_live(mid, client)
                self.assertEqual("unknown", store.inbox("S1", worker)[0]["transport"])
                self.assertEqual(1, client.push.call_count)
            finally:
                store.close()

    def test_inflight_dispatch_blocks_takeover_but_not_independent_acks(self):
        with TemporaryDirectory() as tmp:
            store = team.Team(Path(tmp) / "team.sqlite")
            try:
                owner, worker, replacement = [str(uuid.uuid4()) for _ in range(3)]
                store.join("supervisor", owner, tmp)
                store.join("S1", worker, tmp)
                mid = store.send("supervisor", owner, "S1", "시험")
                def push(*args):
                    other = team.Team(Path(tmp) / "team.sqlite")
                    try:
                        other.ack("S1", worker, mid, "received")
                        with self.assertRaisesRegex(ValueError, "전달 중"):
                            other.join("S1", replacement, tmp, takeover=True)
                    finally:
                        other.close()
                    return "turn"
                client = Mock()
                client.target.return_value = {"cwd": tmp}
                client.push.side_effect = push
                with patch.object(live, "same_repo"):
                    store.deliver_live(mid, client)
                self.assertEqual("received", store.inbox("S1", worker)[0]["state"])
            finally:
                store.close()


if __name__ == "__main__":
    unittest.main()
