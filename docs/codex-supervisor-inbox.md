# 감독 inbox와 PR 감시

## 공개 인터페이스 확인

설치된 `codex-cli 0.172.0`에서 `codex features list`와 `codex app-server generate-json-schema --out <임시 경로>`를 확인했다. `agent_message_board`와 `wake_on_agent_messages`는 개발 중 기능으로 표시되며, 기본 프로토콜 스키마와 공개 TUI 설정에서 프로젝트 메시지 패널과 원문 펼치기를 연결하는 인터페이스를 찾지 못했다. 기본 TUI의 `turn/start`는 새 턴을 시작하고, `turn/steer`는 진행 중인 턴에 입력을 추가한다. 이 두 동작은 기존 긴급 전달에 사용한다. [App Server 문서](https://learn.chatgpt.com/docs/app-server), [TUI 설정 문서](https://learn.chatgpt.com/docs/config-file/config-advanced)

## 동작과 검증 기준

- #743의 팀 SQLite와 역할 소유권을 사용한다. 감독에게 보낸 일반 보고는 전경 실행 여부와 관계없이 보관한다. 보관한 원문과 키로 같은 이벤트를 다시 저장하면 기존 메시지 ID를 반환한다.
- 별도 터미널에서 등록된 감독의 inbox를 열어 발신자, 이슈·작업, 현지 시각, 첫 줄 요약과 읽지 않은 수를 본다. 번호로 원문을 열면 역할에 읽은 시각을 기록한다. 수신·착수·완료는 기존 ack 명령에서 각각 기록한다.
- 작업 진행을 막는 결정 요청에는 `--live --event-key`를 명시한다. 같은 이벤트 키로 명령을 반복해도 추가 전달하지 않는다. App Server 응답이 불확실하면 기존 inbox에서 원문을 확인한다. 팀 메시지는 사용자 승인이나 권한 확대의 근거가 될 수 없다.
- 10분 PR 감시의 등록 브랜치와 시작 범위를 입력으로 받는다. 열린 PR과 CI 완료는 보관하고, 충돌·CI 실패·머지는 감독이 처리할 후속 항목으로 남긴다. 기존 작업자 배정, PR 검증, CI 수정과 사용자 머지 후 정리의 실행 경로를 유지한다.
- Windows에서는 격리된 팀 DB로 여러 프로세스의 보고, 원문 펼치기, 재접속과 ack를 확인한다. PR 감시의 관측·중복 처리와 후속 신호는 GitHub 응답을 고정해 검증한다.