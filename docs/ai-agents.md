# Codex · Cursor 프로젝트 설정

기존 Claude Code의 스킬 10개와 역할 10개를 Codex·Cursor에서도 사용한다.
도메인·개발 규칙의 정본은 `backend/docs/`, `frontend/docs/`, Git 규칙은 [git-workflow.md](git-workflow.md)다.
Claude의 모델명과 `tools` 메타데이터는 다른 도구의 설정으로 복사하지 않는다.

## 초기화된 구성

```text
AGENTS.md                              저장소 공통 초기 지침
backend/AGENTS.md                      백엔드 초기 지침
frontend/AGENTS.md                     프론트엔드 초기 지침
.agents/skills/<name>/SKILL.md          Codex·Cursor 공통 스킬 10개
.agents/roles/<name>.md                도구 중립 역할 지침 10개
.cursor/rules/commerce-foundry.mdc      Cursor의 항상 적용되는 프로젝트 진입 규칙
scripts/sync-agent-skills.py            Claude 원본 → 공통 스킬·역할 동기화 및 검사
```

Codex와 Cursor가 모두 `.agents/skills/`를 탐색하므로 두 도구가 같은 파일을 사용한다.
`.codex/skills/`와 `.cursor/skills/`에 사본을 추가할 필요는 없다. 심볼릭 링크나 전역 설치도 필요하지 않다.
Cursor는 Claude 호환 경로도 탐색하므로 같은 이름의 항목이 함께 보이면 `.agents/skills/` 경로의 정의를 선택한다.
프로젝트 진입 규칙에도 이 경로를 명시했다.

이 저장소의 init은 위 초기 지침과 프로젝트 규칙을 만드는 작업이다. Git 저장소를 다시 만들거나 앱 의존성·DB를 설치하는 작업은 포함하지 않는다.
새 Codex 세션 또는 새 Cursor Agent 대화에서 저장소를 열어 사용한다. 이미 열린 세션에서 목록이 갱신되지 않으면 세션을 다시 연다.

## 스킬 호출

| 용도 | Codex | Cursor |
|------|-------|--------|
| 이슈 작성·등록 | `$issue` | `/issue` |
| 이슈 번호로 브랜치·계획 시작 | `$issue-start` | `/issue-start` |
| develop 대상 PR 생성 | `$pr` | `/pr` |
| 개발 유형·복잡도와 역할 선택 | `$dev-orchestrator` | `/dev-orchestrator` |
| 백엔드 새 서비스·컨텍스트 착수 | `$backend-feature-bootstrap` | `/backend-feature-bootstrap` |
| API 계약 점검 | `$backend-api-check` | `/backend-api-check` |
| Hexagonal 계층 점검 | `$hexagonal-guard` | `/hexagonal-guard` |
| 결제 정합성 점검 | `$payment-guard` | `/payment-guard` |
| 주문·재고·배송·클레임 점검 | `$order-flow-guard` | `/order-flow-guard` |
| 큰 백엔드 작업의 역할 분담 | `$backend-multi-agent` | `/backend-multi-agent` |

예: Codex에서는 `$issue-start 12`, Cursor에서는 `/issue-start 12`.
스킬 본문의 `/스킬명` 표기는 동일한 워크플로를 뜻하며 Codex에서는 `$스킬명`으로 호출한다.
두 도구 모두 작업 설명에 맞는 스킬을 자동 선택할 수 있다. 스킬 적용이 요청 범위 밖 외부 작업을 승인하지는 않는다.

`issue`·`issue-start`·`pr`의 원격 조회·등록에는 GitHub CLI 설치와 해당 저장소에 접근할 수 있는 `gh` 인증이 필요하다.
기존처럼 담당자·라벨·이슈 번호·develop base 규칙을 유지한다. 초안만 요청하면 등록·푸시하지 않는다.
멀티라인 본문은 UTF-8 임시 파일에 저장해 `--body-file`로 전달한다. PowerShell에서는 Bash의 줄 연결 `\` 대신 명령을 한 줄로 작성한다.

## 역할과 실행 권한

| 구분 | 역할 | 작업 범위 |
|------|------|-----------|
| 공용 | `explorer`, `architect`, `reviewer` | 탐색·설계·최종 검토, 읽기 전용 |
| BE 구현 | `be-executor` | 명세·계획에 따른 백엔드 구현과 검증 |
| BE 검토 | `be-hexagonal-reviewer`, `be-db-reviewer`, `be-security-reviewer`, `be-payment-reviewer` | 계층·영속성·보안·결제 검토, 읽기 전용 |
| FE 구현 | `fe-implementer` | 프론트엔드 구현과 검증 |
| FE 검토 | `fe-reviewer` | BFF·쿠키·금액·멱등·테마 검토, 읽기 전용 |

`.agents/roles/`는 **역할 지침 파일**이다. 이 디렉터리만으로 Codex·Cursor에 네이티브 하위 에이전트가 등록되지는 않는다.
메인 세션이 필요한 파일을 읽어 역할을 수행하거나, 현재 도구에서 하위 에이전트를 사용할 수 있고 위임이 허용된 경우 그 지침을 전달한다.
지원되지 않거나 위임이 허용되지 않으면 동일한 탐색 → 설계 → 구현 → 검증 → 검토를 메인 세션에서 순차 수행한다.
실제 실행한 역할과 수행 방식을 결과에 기록한다.

- 모델은 현재 도구·세션의 설정을 따른다. Claude의 Fable·Opus·Sonnet 모델명을 Codex·Cursor 모델명으로 치환하지 않는다.
- 검토·탐색·설계 역할은 소스·설정·문서를 편집하지 않는다. 실행 환경이 읽기 전용 도구 제한을 지원하면 함께 적용한다.
  역할 파일 자체가 도구 권한을 강제하는 것은 아니다. 검사 실행은 빌드 산출물을 만들 수 있다.
- 독립적인 읽기 전용 조사·검토만 병렬로 수행한다. 쓰기 역할은 한 번에 하나만 수행한다.
- 하위 역할은 재위임·커밋·푸시·이슈·PR을 수행하지 않는다. 메인 세션이 요청 범위 안에서 처리한다.
- 메인 세션이 범위·계획·최종 결과를 판단하고 실제 검증 결과를 확인한다. 하위 역할의 통과 보고만으로 완료 처리하지 않는다.

## 동기화와 검증

Claude 원본은 `.claude/skills/`, `.claude/agents/`이며 기존 파일은 그대로 유지한다.
변경 후 루트에서 아래 명령을 실행한다. Python 3의 표준 라이브러리만 사용한다.

```bash
python -X utf8 scripts/sync-agent-skills.py --write
python -X utf8 scripts/sync-agent-skills.py
git diff --check
```

첫 명령은 `.agents/skills/`와 `.agents/roles/`를 재생성한다. 공통 스킬·역할을 바꾸려면 원본 또는 변환 스크립트를 수정한다.
두 번째 명령은 파일을 쓰지 않고 동기화 여부를 검사한다. 새 스킬을 추가하면 스크립트의 `DESCRIPTIONS`에 탐색용 설명도 추가한다.
삭제된 원본의 결과 파일이 남아 있으면 자동 삭제하지 않고 `stale`로 보고한다.
`AGENTS.md` 3개와 Cursor 규칙은 직접 관리한다. 공통·워크스페이스 규칙을 바꾸면 대응 `CLAUDE.md`도 함께 확인한다.

설정 확인 시 에이전트에게 읽은 초기 지침 경로와 사용 가능한 프로젝트 스킬 이름을 요청한다.
형식·경로 검사와 각 앱의 실제 빌드·테스트는 별개다. 런타임을 바꾸면 각 워크스페이스의 검증 명령을 실행한다.

공식 문서: [Codex 스킬](https://developers.openai.com/codex/skills/), [Codex AGENTS.md](https://developers.openai.com/codex/guides/agents-md/),
[Cursor 스킬](https://cursor.com/docs/skills), [Cursor 규칙](https://cursor.com/docs/rules).
