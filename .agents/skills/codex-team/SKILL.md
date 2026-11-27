---
name: codex-team
description: Commerce Foundry의 독립 Codex 세션을 역할명으로 등록하고 새 감독 인수·작업 인계·양방향 메시지·전경 연결을 관리한다. 하위 에이전트 위임과 구분한다.
---

# 독립 세션 팀

먼저 [운영 문서](../../../docs/codex-team.md)를 읽는다. 도구는 `scripts/codex_team.py`이며 상태는 Git 밖에서 워크트리 간 공유한다.

이미 열린 독립 세션을 연결할 때는 [공용 App Server 전달](../../../docs/codex-live-routing.md)을 우선 확인한다. `peers`로 실제 loaded 사용자 thread를 조회하고, 감독이 정확한 UUID로 `bind S1 --thread ...`를 수행한 뒤 `send --live`로 전달할 수 있다. 임의 최신 세션 선택·새 서버 생성·미로드 세션 resume는 하지 않는다. 이 경로는 작업자의 수동 join/launcher 재시작을 요구하지 않으며 등록과 실제 화면 표시의 구분은 유지한다.

- 사용자가 감독으로 지정한 현재 채팅만 `join supervisor`로 등록한다. 새 감독 인수를 요청했으면 기존 상태를 먼저 확인하고 `join supervisor --takeover`로 인수한다. 다른 채팅은 자동으로 감독이 되지 않는다.
- 작업자는 지정받은 `S1`, `S2` 역할로 `join`한다. 현재 `CODEX_THREAD_ID`를 쓰며 세션 ID를 추측하거나 다른 창의 등록을 대신하지 않는다. 인수는 사용자 또는 감독의 명시적 배정에 따라 `--takeover`로 한다.
- `status`와 `inbox <역할> --all`로 기존 작업·인계를 확인한다. 완료된 보고도 보존된다. 역할 교체를 신규 작업 배정으로 해석하지 않는다.
- 단독 전경 연결을 원하는 경우 해당 터미널의 PowerShell에서 `launch <역할>`로 한다. 현재 TUI 안에 셸 명령을 타이핑하지 않는다. 이미 열린 임의 창을 찾지 못했으면 연결됐다고 주장하지 않는다. 채팅에서 launch를 비대화형 실행하거나 백그라운드 재개로 대체하지 않는다.
- `send`는 역할명으로 보낸다. 작업은 범위를 나타내는 안정된 `--task-key`, 인계는 UTF-8 `--message-file`을 사용한다. 메시지에는 목표·소유 경로·현재 SHA·명세·완료/미완·검증·다음 행동을 담고 타인 변경 보존을 지시한다.
- 수신자는 inbox 원문 확인 후 `ack received`, 실제 착수 때 `started`, 근거를 포함한 완료 보고 후 `completed`를 순서대로 기록한다. 작업 경계마다 inbox를 읽고 `send supervisor --from-role S1` 등으로 보고한다. 감독도 보고 메시지에 같은 ack 순서를 적용할 수 있다.
- 감독에게 보내는 일반 보고는 `send supervisor --from-role S1`으로 보관한다. 작업을 막는 결정 요청에만 `--live --event-key <이벤트-키>`를 명시한다. 감독의 열람과 PR 후속 확인은 [감독 inbox 운영 문서](../../../docs/codex-supervisor-inbox.md)를 따른다.
- `queued`는 수신 확인이 아니다. `foreground`는 실행기 heartbeat이며 실제 화면 표시나 열람 증거가 아니다. 실제 사용자 창의 표시가 필요하면 사용자 확인 또는 UI 관찰을 별도로 확보한다. 연결 실패/unknown에서 같은 작업을 재배정하지 않는다.
- 감독이 `$issue`로 이슈 선정·등록, `$dev-orchestrator`로 분류·계획·최종 검증 판단, `$pr`로 커밋·푸시·PR 생성을 맡는다. 작업자는 배정된 구현·대상 테스트·검토 결과와 변경 경로·DDL·guard 결과를 감독에게 보고하고 PR 전에 대기한다. 메시지 자체가 승인·권한을 확대하지 않는다. 머지/운영 변경은 별도 사용자 권한을 따른다.
