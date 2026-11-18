"""Bounded WebSocket JSON-RPC over the installed Codex control-socket proxy."""
from __future__ import annotations

import base64
import hashlib
import json
import os
from pathlib import Path
import queue
import struct
import subprocess
import threading
import time

MAX_BYTES = 4 * 1024 * 1024


class LiveError(RuntimeError):
    pass


class Client:
    def __init__(self, executable, timeout=8):
        self.timeout = timeout
        self.buffer = b""
        self.counter = 0
        self.chunks = queue.Queue(maxsize=128)
        self.closed = threading.Event()
        self.process = subprocess.Popen(
            [executable, "app-server", "proxy"], stdin=subprocess.PIPE,
            stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()
        try:
            key = base64.b64encode(os.urandom(16)).decode("ascii")
            self._write(("GET / HTTP/1.1\r\nHost: localhost\r\n"
                "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n\r\n").encode("ascii"))
            deadline = time.monotonic() + timeout
            while b"\r\n\r\n" not in self.buffer:
                if len(self.buffer) > 8192:
                    raise LiveError("공용 서버 handshake가 너무 큽니다")
                self.buffer += self._chunk(deadline)
            headers, self.buffer = self.buffer.split(b"\r\n\r\n", 1)
            lines = headers.decode("ascii").split("\r\n")
            fields = dict(line.split(":", 1) for line in lines[1:] if ":" in line)
            fields = {k.lower(): v.strip() for k, v in fields.items()}
            expected = base64.b64encode(hashlib.sha1(
                (key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode("ascii")).digest()).decode("ascii")
            if not lines[0].startswith("HTTP/1.1 101 ") or fields.get("sec-websocket-accept") != expected:
                raise LiveError("공용 서버 WebSocket handshake가 일치하지 않습니다")
            self.call("initialize", {"clientInfo": {"name": "commerce-foundry-team", "version": "1"}})
            self.send({"method": "initialized"})
        except BaseException:
            self.close()
            raise

    def _read(self):
        try:
            while not self.closed.is_set():
                chunk = self.process.stdout.read1(65536)
                while not self.closed.is_set():
                    try:
                        self.chunks.put(chunk, timeout=.1)
                        break
                    except queue.Full:
                        continue
                if not chunk:
                    return
        except (OSError, ValueError):
            pass

    def _chunk(self, deadline):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise LiveError("공용 서버 응답 시간 초과")
        try:
            chunk = self.chunks.get(timeout=remaining)
        except queue.Empty as exc:
            raise LiveError("공용 서버 응답 시간 초과") from exc
        if not chunk:
            raise LiveError("공용 서버 연결이 종료되었습니다")
        return chunk

    def _take(self, size, deadline):
        while len(self.buffer) < size:
            self.buffer += self._chunk(deadline)
        result, self.buffer = self.buffer[:size], self.buffer[size:]
        return result

    def _frame(self, opcode, payload):
        if len(payload) > MAX_BYTES:
            raise LiveError("공용 서버 프레임 크기 제한 초과")
        header = bytes([128 | opcode])
        size = len(payload)
        if size < 126:
            header += bytes([128 | size])
        elif size < 65536:
            header += bytes([254]) + struct.pack("!H", size)
        else:
            header += bytes([255]) + struct.pack("!Q", size)
        mask = os.urandom(4)
        encoded = bytes(value ^ mask[i % 4] for i, value in enumerate(payload))
        self._write(header + mask + encoded)

    def _stop_proxy(self):
        if self.process.poll() is None:
            self.process.terminate()
        try:
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=3)

    def _write(self, payload):
        done = threading.Event()
        errors = []
        def write():
            try:
                self.process.stdin.write(payload)
                self.process.stdin.flush()
            except (OSError, ValueError) as exc:
                errors.append(exc)
            finally:
                done.set()
        writer = threading.Thread(target=write, daemon=True)
        writer.start()
        if not done.wait(self.timeout):
            self._stop_proxy()
            writer.join(timeout=1)
            raise LiveError("공용 서버 전송 시간 초과. 본인 proxy만 종료했습니다")
        if errors:
            raise LiveError("공용 서버 전송 연결이 종료되었습니다")

    def send(self, message):
        self._frame(1, json.dumps(message, ensure_ascii=False).encode("utf-8"))

    def receive(self, deadline):
        fragments, total = [], 0
        while True:
            if time.monotonic() >= deadline:
                raise LiveError("공용 서버 응답 시간 초과")
            first, second = self._take(2, deadline)
            fin, opcode, size = first & 128, first & 15, second & 127
            if first & 112 or second & 128:
                raise LiveError("지원하지 않는 WebSocket 프레임")
            if size == 126:
                size = struct.unpack("!H", self._take(2, deadline))[0]
            elif size == 127:
                size = struct.unpack("!Q", self._take(8, deadline))[0]
            if size > MAX_BYTES or total + size > MAX_BYTES:
                raise LiveError("공용 서버 메시지 크기 제한 초과")
            if opcode >= 8 and (not fin or size > 125):
                raise LiveError("잘못된 WebSocket 제어 프레임")
            payload = self._take(size, deadline)
            if opcode == 8:
                raise LiveError("공용 서버 WebSocket 종료")
            if opcode == 9:
                self._frame(10, payload)
                continue
            if opcode == 10:
                continue
            if (opcode == 1 and fragments) or (opcode == 0 and not fragments) or opcode not in (0, 1):
                raise LiveError("잘못된 WebSocket 분할 프레임")
            fragments.append(payload)
            total += size
            if fin:
                return json.loads(b"".join(fragments).decode("utf-8"))

    def call(self, method, params):
        self.counter += 1
        request_id = self.counter
        self.send({"id": request_id, "method": method, "params": params})
        deadline = time.monotonic() + self.timeout
        while True:
            if time.monotonic() >= deadline:
                raise LiveError("공용 서버 응답 시간 초과")
            message = self.receive(deadline)
            if not isinstance(message, dict):
                raise LiveError("JSON-RPC 응답 형식 오류")
            if message.get("id") != request_id:
                continue
            if "error" in message:
                raise LiveError(f"공용 서버 {method} 요청 거절")
            if "result" not in message:
                raise LiveError("JSON-RPC result가 없습니다")
            return message["result"]

    def loaded(self):
        ids = self.call("thread/loaded/list", {})["data"]
        if not isinstance(ids, list) or len(ids) > 100:
            raise LiveError("로드된 세션 목록 범위를 확인하세요")
        return ids

    def target(self, thread):
        if thread not in self.loaded():
            raise LiveError("현재 공용 서버에 로드되지 않은 세션입니다. 자동 resume하지 않습니다")
        row = self.call("thread/read", {"threadId": thread, "includeTurns": False})["thread"]
        if not independent(row):
            raise LiveError("독립 사용자 세션만 연결할 수 있습니다")
        return row

    def push(self, thread, text, repo=None):
        row = self.target(thread)
        if repo is not None:
            same_repo(row["cwd"], repo)
        status = row["status"]["type"]
        if status == "idle":
            result = self.call("turn/start", {"threadId": thread,
                "input": [{"type": "text", "text": text}]})
            return result["turn"]["id"]
        if status == "active":
            turns = self.call("thread/turns/list", {"threadId": thread,
                "limit": 1, "sortDirection": "desc", "itemsView": "notLoaded"})["data"]
            active = [t for t in turns if t["status"] == "inProgress"]
            if len(active) == 1:
                result = self.call("turn/steer", {"threadId": thread,
                    "expectedTurnId": active[0]["id"], "input": [{"type": "text", "text": text}]})
                return result["turnId"]
        raise LiveError("세션 상태가 변경됐거나 입력을 받을 수 없습니다. 자동 재전송하지 않습니다")

    def close(self):
        self.closed.set()
        try:
            self._stop_proxy()
        finally:
            for stream in (self.process.stdin, self.process.stdout):
                stream.close()
            self.reader.join(timeout=1)


def independent(row):
    source = row.get("source", "")
    return not (row.get("ephemeral") or
                (isinstance(source, str) and source.startswith("subAgent")) or
                (isinstance(source, dict) and "subAgent" in source))


def same_repo(cwd, expected):
    def common(path):
        result = subprocess.run(["git", "rev-parse", "--path-format=absolute", "--git-common-dir"],
            cwd=Path(str(path).removeprefix("\\\\?\\")), capture_output=True, text=True, encoding="utf-8")
        if result.returncode:
            raise LiveError("세션의 Git 저장소를 확인할 수 없습니다")
        return Path(result.stdout.strip()).resolve()
    if common(cwd) != common(expected):
        raise LiveError("다른 저장소의 세션에는 전달하지 않습니다")
