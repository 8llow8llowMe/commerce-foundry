# 독립 Codex 세션 팀

이 프로젝트는 `scripts/codex_team.py`로 새·기존 독립 세션에 `supervisor`, `S1`, `S2` 같은 역할을 붙인다. 새 채팅에서도 역할을 인수해 이전 작업·메시지를 읽을 수 있다. 하위 에이전트와는 별개다. Python 3와 설치된 Codex CLI가 필요하다.

## 새 감독 채팅에서 지시하기

> 이 프로젝트의 감독 역할을 인수해줘. `$codex-team`을 적용하고 기존 역할·인계·미완료 작업을 확인해. 이전 작업을 중복 배정하지 말고 전경 연결된 S1·S2와 협업해. 개발은 `$dev-orchestrator`, PR은 `$pr`을 적용하고 머지는 내가 할게.

스킬이 목록에 아직 보이지 않으면 `.agents/skills/codex-team/SKILL.md`를 읽으라고 덧붙인다. 감독 세션 안에서 다음을 실행한다. `CODEX_THREAD_ID`로 현재 채팅을 식별한다. 첫 등록에는 `--takeover`가 필요 없고, 기존 감독에서 새 감독으로 바꿀 때 명시적으로 붙인다.

```powershell
python -B -X utf8 scripts/codex_team.py join supervisor --takeover
python -B -X utf8 scripts/codex_team.py status
python -B -X utf8 scripts/codex_team.py inbox supervisor --all
```

같은 역할을 다른 세션에 조용히 덮어쓰지 않는다. 이전 감독은 인수 후 해당 역할로 보낼 수 없다. 이미 전경 실행 중인 역할은 터미널 종료 후 인수한다. 완료된 보고도 `--all`로 읽을 수 있다.

## 작업자 터미널 연결

빈 Codex 채팅 안에서는 다음처럼 지시한다.

> `$codex-team`으로 이 세션을 S1에 등록하고 기존 inbox·인계를 확인해. 아직 코드를 바꾸지 말고 감독에게 수신 확인을 보내. 한국어로 진행하고 `$dev-orchestrator`·`$pr`을 적용해.

`join S1`은 현재 세션의 신원 등록이다. **등록만으로 화면에 연결됐다고 표시하지 않는다.** 전경 실행을 사용하려면 현재 Codex를 정상 종료한 뒤 그 터미널의 PowerShell에서 다음을 실행한다. 이미 등록된 역할이면 해당 세션을 재개하고, 미등록이면 새 세션을 만들어 시작 지시로 등록한다.

```powershell
python -B -X utf8 scripts/codex_team.py launch S1
python -B -X utf8 scripts/codex_team.py launch S2 --cwd D:/ProjectWorkSpace/commerce-foundry
```

두 명령은 **서로 다른 사용자가 보는 터미널**에서 실행한다. `Start-Process -WindowStyle Hidden`, 비대화형 exec, native `send_message_to_thread`로 실행하는 대체 방식은 화면 연결 검증이 아니다. 새 역할로 작업자를 교체할 때는 새 세션에서 `join S1 --takeover` 후 종료하고 `launch S1`로 연다. `launch S1 --thread <UUID>`는 등록한 역할과 같은 과거 세션을 명시할 때 쓴다.

실행기는 stdin/stdout을 해당 터미널에 상속하고 `codex --no-daemon`으로 실행한다. 다른 세션의 공유 데몬을 중지하지 않는다. 종료 시 본인이 띄운 프로세스만 정리하며, Windows에서는 그 프로세스 트리를 종료한다. 비정상 종료로 남은 heartbeat는 30초 뒤 만료된다. heartbeat는 실행기 생존 증거이며 화면 픽셀이나 사용자의 열람 증거는 아니다. 프로세스 강제 종료 중 외부 부작용의 취소를 보장하지 않으므로 작업 중에는 먼저 작업자에게 안전 중단을 지시한다.

Windows npm 설치에서는 실제 `codex.exe`를 찾아 쉘 없이 실행한다. 다른 설치 구조에서는 실제 실행파일 절대경로를 `CODEX_TEAM_BIN`에 지정한다. 승인·sandbox·모델 설정은 기존 Codex 설정을 사용하며 실행기가 권한을 확대하지 않는다.

## 배정·보고·명시적 수신 확인

다음은 해당 역할로 등록된 **Codex 채팅 안에서** 실행한다. 긴 한국어 지시는 UTF-8 파일로 저장한다. `--task-key`는 동일 작업을 동시에 배정하지 않기 위한 안정된 식별자다. `#20`의 서로 다른 단계라면 `20/price-source`처럼 범위를 담는다. 작업자 한 명에게 미완료 배정은 한 건만 둔다.

```powershell
python -B -X utf8 scripts/codex_team.py send S1 --from-role supervisor --task-key 582/restoration --message-file D:/handoff.txt
python -B -X utf8 scripts/codex_team.py inbox S1
python -B -X utf8 scripts/codex_team.py ack S1 <메시지-ID> received
python -B -X utf8 scripts/codex_team.py ack S1 <메시지-ID> started
python -B -X utf8 scripts/codex_team.py send supervisor --from-role S1 --message '설계 대조 완료. 구현 전 분리안을 보고합니다.'
python -B -X utf8 scripts/codex_team.py ack S1 <메시지-ID> completed
```

`completed`는 작업자의 보고 상태이며 검증·머지·이슈 종료를 대신하지 않는다. 감독은 실제 근거를 대조한다. 미완 작업은 상태를 완료로 바꾸지 않고 역할 인수와 inbox로 이어간다. 작업자끼리도 같은 `send S2 --from-role S1` 형식으로 협의할 수 있다.

상태를 구분한다.

| 항목 | 의미 |
|---|---|
| `foreground: false` | 실행기 연결 없음. 메시지는 저장하지만 세션을 자동 재개하지 않음 |
| `transport: pending` | 외부 inbox에 저장됨. 전경 실행기가 전달할 때까지 대기 |
| `transport: queued` | Codex 큐 등록 성공. 화면 표시·수신·작업 시작은 아직 증명하지 않음 |
| `transport: dispatching/unknown` | 전달 도중 중단 또는 실패. 중복 방지를 위해 자동 재전송하지 않음 |
| `state: received/started/completed` | 해당 역할 소유 세션이 각각 수신·착수·완료를 명시적으로 기록 |

연결이 끊겨도 메시지와 작업은 남는다. `unknown`은 `inbox` 원문으로 처리하고 ack하면 된다. 새 작업을 다시 만들어 중복 실행하지 않는다. 작업자는 인계 직후와 작업 경계마다 inbox를 확인한다. 대기 중 Codex의 큐 소비·화면 갱신은 CLI 버전에 의존하므로 `queued`에서 멈췄다면 터미널에서 inbox 확인을 지시한다. 이 도구는 Codex TUI 자체를 패치하거나 임의의 빈 터미널을 찾아 붙이지 않는다.

## 상태·기존 감독 도구와의 관계

Git common-dir 기준 저장소 옆 `.commerce-foundry-codex-team/team.sqlite`를 모든 워크트리가 공유한다. 로컬 세션·메시지 원문은 공개 Git에 커밋하지 않는다. SQLite 트랜잭션과 미완료 task key unique 인덱스로 동시 배정을 제어한다. 다른 로컬 사용자를 차단하는 인증 서버는 아니다.

기존 `be_queue_supervisor.py`는 별도 작업/머지 감독 상태이며 이 팀 도구는 통신과 인계를 담당한다. 동일 작업을 양쪽에서 새로 배정하지 않는다. 특히 기존 `assign`·`codex_session_mail.py send`의 `--resume` 기반 경로는 백그라운드 재개일 수 있으므로 전경 모드 대체로 사용하지 않는다. 이 PR은 기존 BE 배정을 자동 이관하거나 오래된 세션을 깨우지 않는다.

검증 명령: `python -B -X utf8 -m unittest discover -s scripts -p 'test_*.py'`. 실제 두 터미널에서 실행·한국어 메시지·ack 왕복 확인은 별도 수동 검증이다.
