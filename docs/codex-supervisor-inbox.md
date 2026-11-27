# 감독 inbox와 PR 감시

## 공개 인터페이스 확인

설치된 `codex-cli 0.172.0`에서 `codex features list`와 `codex app-server generate-json-schema --out <임시 경로>`를 확인했다. `agent_message_board`와 `wake_on_agent_messages`는 개발 중 기능으로 표시되며, 기본 프로토콜 스키마와 공개 TUI 설정에서 프로젝트 메시지 패널과 원문 펼치기를 연결하는 인터페이스를 찾지 못했다. 기본 TUI의 `turn/start`는 새 턴을 시작하고, `turn/steer`는 진행 중인 턴에 입력을 추가한다. 이 두 동작은 기존 긴급 전달에 사용한다. [App Server 문서](https://learn.chatgpt.com/docs/app-server), [TUI 설정 문서](https://learn.chatgpt.com/docs/config-file/config-advanced)

## 동작과 검증 기준

- #743의 팀 SQLite와 역할 소유권을 사용한다. 감독에게 보낸 일반 보고는 전경 실행 여부와 관계없이 보관한다. 보관한 원문과 키로 같은 이벤트를 다시 저장하면 기존 메시지 ID를 반환한다.
- 별도 터미널에서 등록된 감독의 inbox를 열어 발신자, 이슈·작업, 현지 시각, 첫 줄 요약과 읽지 않은 수를 본다. 번호로 원문을 열면 역할에 읽은 시각을 기록한다. 수신·착수·완료는 기존 ack 명령에서 각각 기록한다.
- 작업 진행을 막는 결정 요청에는 `--live --event-key`를 명시한다. 같은 이벤트 키로 명령을 반복해도 추가 전달하지 않는다. App Server 응답이 불확실하면 기존 inbox에서 원문을 확인한다. 팀 메시지는 사용자 승인이나 권한 확대의 근거가 될 수 없다.
- 10분 PR 감시의 등록 브랜치와 시작 범위를 입력으로 받는다. 열린 PR과 CI 완료는 보관하고, 충돌·CI 실패·머지는 감독이 처리할 후속 항목으로 남긴다. 기존 작업자 배정, PR 검증, CI 수정과 사용자 머지 후 정리의 실행 경로를 유지한다.
- Windows에서는 격리된 팀 DB로 여러 프로세스의 보고, 원문 펼치기, 재접속과 ack를 확인한다. PR 감시의 관측·중복 처리와 후속 신호는 GitHub 응답을 고정해 검증한다.

## 운영 명령

별도 PowerShell에서 등록된 감독의 세션 UUID로 연다. `status`의 `members`에서 현재 감독 UUID를 확인할 수 있다. 이 UUID는 외부 사용자를 인증하는 비밀키가 아니며 팀 DB에 접근할 수 있는 로컬 사용자 환경을 전제로 한다.

```powershell
python -B -X utf8 scripts/codex_team.py view --thread <감독-세션-UUID>
python -B -X utf8 scripts/codex_team.py view --thread <감독-세션-UUID> --actions
```

번호를 입력하고 Enter를 누르면 원문을 펼친다. Enter로 목록에 돌아온다. `n`·`p`는 다음·이전 쪽, 목록에서 Enter는 최신 쪽 새로고침, `q`는 종료다. 여러 줄 보고의 첫 번째 비어 있지 않은 줄이 미리보기에 표시된다. 목록과 JSON 조회는 읽음 시각을 변경하지 않는다. 원문을 펼쳐도 수신·착수·완료는 기존 `ack` 명령으로 기록한다. 콘솔 제어문자는 이스케이프해서 표시하며 SQLite와 JSON에는 원문을 보존한다.

작업자 세션은 일반 보고를 다음과 같이 저장한다. `--work-key`를 생략하면 미완료 배정의 task key를 사용한다. 해당 이슈가 다르거나 이미 배정을 완료했다면 명시한다. 같은 이벤트를 재시도하는 발신자는 동일한 `--event-key`를 사용한다. 이 키는 발신·수신 역할 쌍에 귀속되며 같은 키를 반복하면 최초 원문을 보존한다.

```powershell
python -B -X utf8 scripts/codex_team.py send supervisor --from-role S1 --work-key '#777' --event-key '777/검증/1' --message '대상 검증 완료'
python -B -X utf8 scripts/codex_team.py send supervisor --from-role S1 --work-key '#777' --event-key '777/결정/1' --live --message-file D:/blocking-question.txt
```

`held`는 inbox에 보관된 상태다. 기존 감독 보고 중 `pending`으로 남은 메시지도 새 실행기에서 보관한다. `live_pending`은 긴급 전달 요청을 기록했으며 App Server 접수 전인 상태다. 접수 실패 또는 결과 불확실 시 저장한 원문을 inbox에서 확인한다. 동일 키 재호출로 추가 전송하지 않는다. `live_accepted` 이후 수신 확인은 기존 ack를 사용한다.

## 10분 PR 감시

Windows 작업 스케줄러에서 10분마다 아래 명령을 한 번 실행할 수 있다. 기존 감시와 같은 `since` 및 `branches` JSON을 사용한다. `since`는 시간대가 있는 ISO 8601 문자열이다. `branches`는 브랜치별 `role`과 `issue`를 담는다. 예제는 공유 설정·로그와 별도로 저장한다.

```json
{
  "since": "2026-11-27T00:00:00Z",
  "branches": {
    "feature/be/123-example": {"role": "S1", "issue": 123}
  }
}
```

```powershell
python -B -X utf8 scripts/codex_pr_watch.py --config <작업-JSON> --legacy-state <이전-감시-상태-JSON>
```

기존 운영 작업을 전환할 때는 감독이 중복 실행을 막고 위 명령으로 교체한다. 이전 상태 파일이 있을 경우 `--legacy-state`를 지정한다. 이전 상태 파일과 팀 DB에 남은 감시 메시지는 현재 PR 조회 결과와 별도로 이관한다. 원문·ID·기존 ack를 유지하고 발신을 PR 감시로 표시한다. 새 설치는 `--legacy-state`를 생략한다.

감시는 GitHub의 develop 대상 PR 중 등록 브랜치만 조회한다. 저장 전 PR 상세의 head SHA를 목록과 대조하며, 동일 PR·SHA의 동일 상태를 반복 저장하지 않는다. 감시 재시작 시에도 중복 방지는 SQLite에 남는다. 일반 OPEN·CI 완료 보고와 미완료 후속 항목은 모두 inbox에 저장된다. GitHub 조회 범위는 기존과 동일하게 최근 PR 100개다.

- 충돌과 CI 실패는 `view --actions` 또는 감독 세션의 `inbox supervisor --actions`에서 확인한다. 감독은 최신 PR 상태와 담당 슬롯을 확인하고 필요한 수정과 재검증을 작업자에게 배정한다.
- 머지는 같은 목록에서 확인한다. develop 반영, PR HEAD, 소유 해제와 clean 여부를 확인한 뒤 [Git 협업 절차](git-workflow.md#7-2-로컬-정리)에 따라 완료된 작업만 정리하고 이슈를 갱신한다. [BE 감독 도구](be-codex-supervisor.md)로 별도 등록한 작업에는 해당 도구의 기존 후속 절차를 사용한다.
- 감독은 후속 처리까지 확인한 뒤 해당 이벤트를 `completed`로 기록한다. 읽음만으로는 후속 목록에서 사라지지 않는다.

검증 환경에서는 자식 프로세스 환경에 `CODEX_TEAM_DB`를 절대 경로로 설정해 별도 SQLite를 사용한다. 운영 실행은 이 변수를 설정하지 않으면 Git common-dir 기준 팀 DB를 사용한다.

## 검증 기록

- Windows Python 3.13.5에서 `python -B -X utf8 -m unittest discover -s scripts -p 'test_*.py'`로 77개 테스트를 실행했다. 이관·중복·PR 후속 신호는 격리 SQLite와 고정 GitHub 응답으로 검증했다.
- Windows ConPTY에서 등록된 감독 UUID로 별도 뷰를 열고 숫자·Enter로 한국어 두 줄 원문을 펼쳤다. 같은 메시지의 읽음 기록과 `pending` ack 상태를 다시 조회했다.
- Codex CLI 0.172.0의 별도 stdio App Server에 임시 세션 세 개를 로드했다. 로컬 모의 모델로 감독의 한 턴을 유지하며 두 작업자가 보고했고, 유휴 중 보고와 실행 중 보고 모두 새 턴과 추가 사용자 입력을 만들지 않았다. 격리 팀 DB에서 한국어 원문, 배정과 ack 왕복을 확인했다. 긴급 전달 접수와 중복 차단은 App Server 대역 테스트로 검증했다.
