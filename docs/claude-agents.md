# Claude Code 역할별 에이전트 운영 가이드

## 목적

작업의 난이도와 위험도에 맞춰 하위 에이전트(subagent)와 모델을 고른다. 단순 작업에 고비용 추론을 반복하지 않으면서,
결제·주문·보안 작업에는 충분한 검토 깊이를 확보하는 것이 목적이다.

정의 파일은 `.claude/agents/*.md` 이므로 이 저장소를 여는 다른 PC·세션에도 똑같이 적용된다.
개인 인증·권한 설정(`.claude/settings.local.json`)은 저장소에 넣지 않는다.

## 운영 결론

**모든 대화에서 에이전트를 자동으로 병렬 실행하지 않는다.** 역할 파일은 항상 발견 가능하게 두고,
`dev-orchestrator` 가 개발 작업을 분류해 필요한 역할만 호출한다.

- 명시 호출: `/dev-orchestrator 작업 내용`
- 직접 요청: `be-payment-reviewer 로 이 브랜치 결제 흐름 검토해줘`

## 역할과 등급

### 공용

| 역할 | 등급 (기본 model · effort) | 권한 | 사용 시점 |
|------|------|------|-----------|
| `explorer` | 빠름 (haiku · medium) | 읽기 전용 | 파일 위치, 호출 흐름, 서비스 간 의존, 영향 범위 |
| `git-ops` | 빠름 (haiku · low) | git·gh 만 (소스 수정 금지) | 브랜치·워크트리, 지정 경로 커밋·푸시, 이슈·PR, 머지 후 정리. `/issue-start`·`/pr` 이 이 역할로 포크된다 |
| `architect` | 설계 (fable · xhigh) | 읽기 전용 | 서비스 경계, 서비스 간 정합성, 보안 구조, 메시징 도입 설계 |
| `reviewer` | 핵심 (opus · high) | 읽기 전용 | 최종 diff 의 정확성·회귀·보안·테스트 검토 |

### 백엔드

| 역할 | 등급 (기본 model · effort) | 권한 | 사용 시점 |
|------|------|------|-----------|
| `be-executor` | 핵심 (opus · high) | 쓰기 | Hexagonal 계층·Port/Adapter 실구현, compile/test 통과까지 |
| `be-hexagonal-reviewer` | 핵심 (opus · high) | 읽기 전용 | 계층 흐름, Port/Adapter 경계, 고객/어드민 진입 분리 |
| `be-db-reviewer` | 핵심 (opus · high) | 읽기 전용 | 엔티티·인덱스·쿼리·락·금액 컬럼 (N+1·초과 판매 우선) |
| `be-security-reviewer` | 핵심 (opus · high) | 읽기 전용 | JWT, ADMIN 경로, 소유 확인(IDOR), 서비스 간 인증(internal API · internal Feign 클라이언트), 비밀정보·개인정보 |
| `be-payment-reviewer` | 핵심 (opus · high) | 읽기 전용 | 결제·주문·재고 정합성 — 실패·중복·지연 주입 표 |

Leader 는 파일이 없다. **메인 세션이 맡는다** (`/backend-multi-agent`).

### 프론트엔드

| 역할 | 등급 (기본 model · effort) | 권한 | 사용 시점 |
|------|------|------|-----------|
| `fe-implementer` | 핵심 (opus · high) | 쓰기 | 앱 화면·route handler·packages 실구현, format·lint·typecheck·test·build 통과까지 |
| `fe-reviewer` | 핵심 (opus · high) | 읽기 전용 | BFF·쿠키 경계, ApiError 처리, 금액·멱등 키, 테마 토큰 규칙 검토 |

새 프론트 역할은 `fe-*` 접두어로 추가하고 이 표에 같은 형식으로 적는다.

## 작업 등급과 모델 · 추론

어려운 일일수록 추론을 높이고, 단순 · 반복 작업은 싼 모델로 내린다. Claude Code 와 Codex 는 **같은 등급**을 쓴다.

| 등급 | 대상 | Claude Code (`model`) | Codex | 추론 (Claude `effort` / Codex `reasoning_effort`) |
|------|------|-----------------------|-------|-----------------------------------------------------|
| 설계 | 서비스 경계 · 교차 모듈 · 정합성 설계 | Fable 5.2 (`fable`) | `gpt-6.2-astra` | `xhigh` 기본, 어려울수록 `max` (Codex 는 `ultra`) |
| 핵심 | 치명 버그 · 결제 · 주문 · 재고 · 인증 등 주요 비즈니스 로직 | Opus 5.5 (`opus`) | `gpt-6.2-sol` | `high` 기본, 꼬인 로직 · 돈 경로는 `xhigh`~`max` |
| 일반 | CRUD 등 단순 개발 | Sonnet 5.5 (`sonnet`) | `gpt-6.1-terra` | `medium`~`high` |
| 빠름 | git · 코드 탐색 · 대량 반복 · 빠른 응답 | Haiku 5.5 (`haiku`) | `gpt-6-luna` | `low`~`medium`, 중요한 코드 탐색은 `high` |

- **역할 파일은 등급 기본값**이다 — `.claude/agents/*.md` frontmatter 의 `model` · `effort`, Codex 는 `.codex/agent-models.json`
  (`models` · `reasoning_effort` · `max_reasoning_effort` 를 등급 키 `design` · `core` · `general` · `fast` 로, 역할 예외는 `role_reasoning_effort`).
  역할 → 등급 매핑은 `scripts/sync-agent-skills.py` 의 `ROLE_TIERS` 하나이고, 검사 모드가 Claude frontmatter 와 Codex 생성본이 이 매핑과 맞는지 본다.
- **호출마다 등급을 고른다.** `/dev-orchestrator` 가 분류(T0~T3 · 위험 신호)로 등급을 정하고 하위 에이전트 호출에 `model` · `effort` 를 넘긴다
  (표는 그 스킬의 "등급 고르기"). Claude Agent 도구의 `effort` 인자는 사용자 · CLAUDE.md · 스킬이 명시할 때만 쓰게 돼 있다 —
  `dev-orchestrator` 의 지시가 그 명시다. Codex 는 `spawn_agent` 의 `model` · `reasoning_effort` 인자로 같은 일을 한다.
- 일반 등급에는 기본 역할이 없다. 구현 · 검토 역할을 `sonnet` / `medium`(Codex `gpt-6.1-terra` / `medium`)으로 낮춰 부른다.
- Cursor 는 `explorer` · `git-ops` 만 `claude-haiku-5-5` 로 고정하고 나머지는 세션 모델(`inherit`)이다. 호출별 effort 지정은 없다.

### 실측 (2026-11-26)

| 항목 | 확인한 값 | 근거 |
|------|-----------|------|
| Claude Agent 도구 `model` | `fable` · `opus` · `sonnet` · `haiku` | Claude Code 도구 정의 |
| Claude Agent 도구 `effort` | `low` · `medium` · `high` · `xhigh` · `max` | Claude Code 도구 정의, frontmatter `effort` 는 `git-ops` 가 먼저 사용 |
| Codex `spawn_agent` 인자 | `agent_type` · `model` · `reasoning_effort` · `fork_turns`(`all` 기본 / `none`) · `task_name` · `message` | codex-cli 0.172.0 세션 기록의 도구 정의 |
| Codex 모델별 추론 수준 | `gpt-6.2-astra` · `gpt-6.2-sol` · `gpt-6.1-terra`: low~max + `ultra` / `gpt-6-luna`: low~max (`ultra` 없음) | `~/.codex/models_cache.json` |

`gpt-6-luna` 는 `ultra` 를 지원하지 않으므로 빠름 등급의 최대는 `high` 로 둔다. 모델을 바꾸면 `--model-catalog` 검사가
등급별 기본 · 최대 추론과 역할 예외를 모델이 지원하는지 본다 ([ai-agents.md](ai-agents.md) "Codex 모델 갱신").

### 공통

- `haiku` 별칭은 Anthropic API 에서 최신 Haiku(현재 **Haiku 5.5**)로 풀린다. Claude Code v2.1.293 이상이 필요하다.
  Bedrock·Vertex 같은 다른 제공자에서는 다른 버전으로 풀릴 수 있다 — 고정하려면 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 을 쓴다.
- 세션 모델과 하위 에이전트 모델은 따로다. 메인 세션의 모델을 하위 에이전트에 상속시키지 않고 호출 인자, 없으면 역할 파일의 `model` 을 쓴다.
- 메인 세션은 계획·범위 결정·최종 완료 판단을 직접 한다. 이 판단을 하위 에이전트에 위임하지 않는다.

## 토큰 절약 규칙

토큰은 "누가 얼마나 읽느냐" 에서 가장 많이 든다. 작업별 라우팅 표는 `/dev-orchestrator` 의 `모델 라우팅` 절이다.

- **단일 명령은 위임하지 않는다.** `git status`·커밋 한 번·위치를 아는 파일 몇 개 읽기는 메인 세션이 직접 한다.
  하위 에이전트는 새 컨텍스트(시스템 프롬프트·도구 정의)로 시작하므로 짧은 일에는 띄우는 비용이 더 크다.
  짧은 일 가운데 위임하는 것은 넓은 탐색(`explorer`)과 여러 단계의 git·gh 작업(`git-ops`)뿐이다. 설계 · 구현 · 검토 위임과 병렬은 아래 병렬화 규칙을 따른다.
- **스킬에 `context: fork` 없이 `model:` 만 두지 않는다.** 그 턴만 메인 세션의 모델이 바뀌는데, 프롬프트 캐시는 모델마다 따로라
  대화 전체를 캐시 없이 다시 읽는다. 긴 세션에서는 싼 모델로 바꿔도 더 비싸다.
- **기계적 스킬은 포크한다.** `/issue-start`·`/pr` 은 `context: fork` + `agent: git-ops` + `model: haiku` 로 새 컨텍스트에서 돌고,
  메인 세션 캐시를 건드리지 않는다. 결과를 기다려야 하므로 `background: false` 다.
  포크는 대화 기록을 보지 못한다 — 메인 세션은 검증 결과 같은 사실을 인자로 넘긴다 (`/pr 검증: ... 참고: ...`).
- `/issue` 는 포크하지 않는다. 대화에서 정한 범위를 이슈로 옮기는 판단이 필요하다.
- `explorer`·`git-ops` 는 `omitClaudeMd: true` 로 CLAUDE.md 를 읽지 않는다. 필요한 저장소 좌표·금지 규칙은 역할 본문에 있다.
- 스킬의 `model`·`effort` 와 `agent` 에 지정한 역할의 값 중 무엇이 이기는지는 문서에 없다. 두 곳에 같은 값을 둔다.

## 권한 규칙

- 검토·탐색·설계 역할은 frontmatter `tools: Read, Grep, Glob, Bash` 로 **읽기 전용을 강제**한다. `Write`/`Edit` 를 넣지 않는다.
  `Bash` 는 `git diff`·검사 실행용이고, 본문에서 편집 금지를 명시한다.
- 쓰기 역할(`be-executor`)은 `tools` 를 생략해 전체 도구를 상속한다.
- `git-ops` 는 `tools: Bash, Read, Grep, Glob, Write` 다. `Write` 는 이슈·PR 본문·커밋 메시지 임시 파일에만 쓰고 소스를 고치지 않는다.
  `git add -A`·`git stash`·강제 푸시·`gh pr merge` 금지와 푸시 전 PR 상태 확인을 역할 본문에 고정했다.
- `git-ops` 외 모든 역할은 **커밋·푸시·이슈·PR 을 만들지 않는다.** 어떤 역할도 하위 에이전트를 다시 만들지 않는다.
  `git-ops` 도 호출자가 정한 범위의 git·gh 작업만 한다.

## 병렬화 규칙

정본은 `/dev-orchestrator` 의 `병렬 실행` 절이다. 요약:

| 패턴 | 요점 |
|------|------|
| (a) 탐색 fan-out | 독립 질문마다 `explorer` 하나, 한 메시지에서 |
| (b) 검토 fan-out | 영역 리뷰어 + 최종 `reviewer` 를 한 메시지에서 동시에, 메인 세션이 합친다 |
| (c) 쓰기 병렬 | 파일 · 모듈 소유권이 겹치지 않을 때만, 쓰기 역할마다 워크트리 격리(Claude `isolation: "worktree"` / Codex 별도 worktree) |
| (d) 파이프라인 | PR 조각 A 검토와 조각 B 구현을 겹친다. B 는 A 의 계약 확정 뒤 |

- **순차로 돌리는 것**: 공개 계약 · DDL · 도메인 공용 객체 · `build.gradle`(공용 빌드 · lockfile) · 같은 문서 한 파일을 둘 이상의 쓰기 역할이 건드릴 때,
  같은 컨텍스트의 수직 조각(Controller → Port → Adapter)을 계층별로 나눌 때,
  앞 단계 결과가 뒤 단계 입력일 때, 같은 PC 에서 Gradle 모듈 check 를 여러 개 동시에 돌릴 때.
- **합치기**: 메인 세션이 격리 워크트리의 소유 경로만 파일 단위로 커밋해(untracked 확인) 작업 브랜치로 `cherry-pick` 하고, 충돌을 직접 푼 뒤
  검증과 검토 fan-out 을 합친 diff 기준으로 다시 돌린다.
- 하위 에이전트의 "통과했다" 를 완료 근거로 쓰지 않는다. 메인 세션이 검증 명령을 다시 돌린다.
- 저장소 엔트리(`CLAUDE.md` · `AGENTS.md`)의 "쓰기 역할은 한 번에 하나만" 은 (c) 의 조건을 만족하지 않는 경우의 기본 규칙이다.

## 주의

사용자 전역(`~/.claude/agents/`)에 같은 이름의 다른 프로젝트용 에이전트가 있을 수 있다. 이 저장소의 프로젝트 스코프 정의가
우선한다. 역할이 이 저장소 규칙(`com.commercefoundry`, `adapter/in/admin`, `payment-guide.md`)을 모르는 것처럼 굴거나
**hondigagae 경로·여행 도메인 용어가 등장하면 잘못된 정의를 읽고 있는 것이다.**

Cursor 는 `.claude/agents/` 도 하위 에이전트로 읽는다. 같은 이름이면 `.cursor/agents/` 가 이기므로, 동기화 스크립트가
Cursor 전용 정의를 생성해 덮어쓴다 ([ai-agents.md](ai-agents.md)). 역할을 추가·변경하면 `--write` 로 다시 생성한다.
