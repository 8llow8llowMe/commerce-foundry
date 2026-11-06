# 독립 Codex BE 작업 세션 감독

`scripts/be_supervisor.py`는 `[BE]` 이슈마다 별도 Git 워크트리와 **독립 Codex CLI 세션**을 만든다. 관리 세션은 상태와 PR을 확인하고 다음 이슈를 대기열에 넣는다. 하위 에이전트 기능은 사용하지 않는다.

## 준비와 운영

Windows PowerShell에서 저장소 루트 기준으로 실행한다. `git`, `gh`, `codex`, Python 3가 PATH에 있어야 하고 `gh auth status`가 성공해야 한다. 워크트리는 저장소 옆 `commerce-foundry-wt/`에, 세션 ID·로그·대기열은 공개 저장소 밖 `.commerce-foundry-be-supervisor/`에 둔다. 로그와 프롬프트는 민감 정보가 포함될 수 있으므로 외부로 공유하지 않는다.

```powershell
python -X utf8 scripts/be_supervisor.py status
python -X utf8 scripts/be_supervisor.py assign 599 store-menu-publish
python -X utf8 scripts/be_supervisor.py queue 598 store-logo-assets
python -X utf8 scripts/be_supervisor.py watch --interval 300
```

`status`는 열린 `[BE]` 이슈 중 기존 세션·워크트리·열린 PR에 연결되지 않은 후보와 세션 상태를 보여준다. 관리자는 이슈 명세와 선행 작업을 확인해 번호와 브랜치 요약을 정한다. `assign`은 `origin/develop`에서 워크트리를 만든 후 독립 Codex 작업을 백그라운드로 시작한다. `queue`는 관리자가 선정한 작업만 대기열에 넣는다. `watch`는 5분 간격으로 머지 확인과 대기열 할당을 반복한다. 동시에 실행 중인 쓰기 세션이나 실패 세션이 있으면 다음 작업을 시작하지 않는다.

PR 리뷰에서 보완이 필요하면 같은 세션에 후속 지시를 보낸다. 대기열의 다음 작업은 앞 작업의 PR 머지와 정리가 모두 끝난 후 시작한다.

```powershell
python -X utf8 scripts/be_supervisor.py resume 599 'PR 리뷰 의견을 반영하고 검증 후 PR을 갱신하세요.'
```

작업 세션은 구현·검증·PR 생성까지 담당한다. `docs/git-workflow.md`에 따라 사용자가 CI와 PR을 확인한 후 **직접 Rebase and merge**한다. 감독 도구는 PR을 머지하지 않는다.

```powershell
python -X utf8 scripts/be_supervisor.py reconcile
```

`reconcile`은 `develop` 대상 머지 PR의 `Issue Number: #번호`와 작업 브랜치를 확인한다. PR의 `## 완료된 이슈 항목`에 정확히 적힌 `- [x] ...` 문구만 이슈 본문의 같은 체크박스에 반영한다. 완료 항목이 없으면 변경하지 않고 수동 검토 대상으로 출력한다. 모든 체크박스가 끝난 이슈만 닫는다. 워크트리가 깨끗하고 해당 브랜치의 커밋이 `origin/develop`에 반영된 경우에만 로컬 워크트리와 브랜치를 정리한다. 원격 브랜치는 저장소 설정이 삭제한다.

실패 상태라면 `.commerce-foundry-be-supervisor/issue-번호.jsonl`을 확인하고 관리자가 원인을 해결한다. 오류가 있는 세션을 자동 재할당하지 않는다. 정리 실패 시 `reconcile`을 재실행할 수 있다. Windows 긴 경로 때문에 `git worktree remove`가 실패하면 `docs/git-workflow.md` §7-2를 따른다.

**운영 경계:** 현재 채팅 세션은 종료 뒤 스스로 깨어나지 않는다. 지속 운영에는 `watch` 프로세스를 별도 터미널이나 작업 스케줄러에서 실행해야 한다. 관리 세션은 `status` 결과와 PR·이슈 내용을 보고 대기열을 갱신한다. 위의 `be_supervisor.py`는 자신이 생성한 세션만 관리하며, 아래의 `be_queue_supervisor.py`는 이미 열린 독립 세션 ID를 등록해 관리한다.

## 이미 열린 독립 Codex 세션 연결

`scripts/be_queue_supervisor.py`는 Codex CLI의 `codex queue`로 **기존 세션 ID**에 메시지를 전달한다. 세션은 `S1`~`S6`으로 등록하고, 이미 푸시된 handoff 브랜치를 해당 이슈 워크트리에 가져온 뒤 배정한다. 감독 상태는 저장소 밖 `.commerce-foundry-be-queue/workers.json`에 기록된다.

```powershell
python -B -X utf8 scripts/be_queue_supervisor.py register S1 <세션-UUID>
python -B -X utf8 scripts/be_queue_supervisor.py assign S1 20 feature/be/20-usd-legacy-price-writers --handoff backend/docs/handoffs/2026-11-06-20-s1.md
python -B -X utf8 scripts/be_queue_supervisor.py status
```

세션이 이미 지시를 받은 뒤 감독 상태만 추가할 때는 `track S5 589 feature/be/589-admin-password-reset`을 사용한다. 각 작업자는 `$dev-orchestrator`와 `$pr`을 적용하고, 검증 후 PR 본문에 실제 완료한 이슈 체크박스 문구를 `## 완료된 이슈 항목`으로 적는다. 사용자만 PR을 Rebase and merge한다.

머지 이후 후속 작업은 슬롯별 대기열에 넣는다. `watch`는 머지된 PR의 브랜치·이슈 연결과 완료 항목, 워크트리 청결도·커밋 반영 여부를 확인하고 이슈 체크·종료·로컬 정리를 수행한 다음 해당 슬롯의 대기열을 배정한다. 근거가 없는 항목은 자동 체크하지 않고 보류한다.

```powershell
python -B -X utf8 scripts/be_queue_supervisor.py queue S1 217 ai-size-chart
python -B -X utf8 scripts/be_queue_supervisor.py watch --interval 300
```

작업 세션의 기본 cwd가 루트이고 워크트리가 쓰기 허용 범위 밖인 경우, 사용자가 해당 세션의 `/permissions`에서 쓰기 범위를 조정해야 한다. `codex queue`가 성공해도 권한 정책이 자동으로 확장되지는 않는다. GitHub 앱/MCP 도구 승인은 로컬 셸 권한과 별도일 수 있다.
