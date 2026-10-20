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

## 역할과 모델

### 공용

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `explorer` | Haiku | 읽기 전용 | 파일 위치, 호출 흐름, 서비스 간 의존, 영향 범위 |
| `git-ops` | Haiku | git·gh 만 (소스 수정 금지) | 브랜치·워크트리, 지정 경로 커밋·푸시, 이슈·PR, 머지 후 정리. `/issue-start`·`/pr` 이 이 역할로 포크된다 |
| `architect` | Fable | 읽기 전용 | 서비스 경계, 서비스 간 정합성, 보안 구조, 메시징 도입 설계 |
| `reviewer` | Opus | 읽기 전용 | 최종 diff 의 정확성·회귀·보안·테스트 검토 |

### 백엔드

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `be-executor` | Opus | 쓰기 | Hexagonal 계층·Port/Adapter 실구현, compile/test 통과까지 |
| `be-hexagonal-reviewer` | Opus | 읽기 전용 | 계층 흐름, Port/Adapter 경계, 고객/어드민 진입 분리 |
| `be-db-reviewer` | Opus | 읽기 전용 | 엔티티·인덱스·쿼리·락·금액 컬럼 (N+1·초과 판매 우선) |
| `be-security-reviewer` | Opus | 읽기 전용 | JWT, ADMIN 경로, 소유 확인(IDOR), 서비스 간 인증(internal API · internal Feign 클라이언트), 비밀정보·개인정보 |
| `be-payment-reviewer` | Opus | 읽기 전용 | 결제·주문·재고 정합성 — 실패·중복·지연 주입 표 |

Leader 는 파일이 없다. **메인 세션이 맡는다** (`/backend-multi-agent`).

### 프론트엔드

| 역할 | 모델 | 권한 | 사용 시점 |
|------|------|------|-----------|
| `fe-implementer` | Opus | 쓰기 | 앱 화면·route handler·packages 실구현, format·lint·typecheck·test·build 통과까지 |
| `fe-reviewer` | Opus | 읽기 전용 | BFF·쿠키 경계, ApiError 처리, 금액·멱등 키, 테마 토큰 규칙 검토 |

새 프론트 역할은 `fe-*` 접두어로 추가하고 이 표에 같은 형식으로 적는다.

## 모델 배정 기준

- **설계·아키텍처 → Fable**, **어려운 구현·검토·정합성 분석 → Opus**, **탐색·git/gh 반복 작업 → Haiku**.
- `haiku` 별칭은 Anthropic API 에서 최신 Haiku(현재 **Haiku 5.5**)로 풀린다. Claude Code v2.1.293 이상이 필요하다.
  Bedrock·Vertex 같은 다른 제공자에서는 다른 버전으로 풀릴 수 있다 — 고정하려면 `ANTHROPIC_DEFAULT_HAIKU_MODEL` 을 쓴다.
- 세션 모델과 하위 에이전트 모델은 따로다. 메인 세션의 모델을 하위 에이전트에 상속시키지 않고 역할 파일의 `model` 을 쓴다.
- 메인 세션은 계획·범위 결정·최종 완료 판단을 직접 한다. 이 판단을 하위 에이전트에 위임하지 않는다.

## 토큰 절약 규칙

토큰은 "누가 얼마나 읽느냐" 에서 가장 많이 든다. 작업별 라우팅 표는 `/dev-orchestrator` 의 `모델 라우팅` 절이다.

- **단일 명령은 위임하지 않는다.** `git status`·커밋 한 번·위치를 아는 파일 몇 개 읽기는 메인 세션이 직접 한다.
  하위 에이전트는 새 컨텍스트(시스템 프롬프트·도구 정의)로 시작하므로 짧은 일에는 띄우는 비용이 더 크다.
  위임은 넓은 탐색(`explorer`)과 여러 단계의 git·gh 작업(`git-ops`)에만 쓴다.
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

- 서로 독립적인 **읽기 전용** 조사·검토만 한 메시지 안에서 병렬로 부른다 (예: hexagonal + db + payment 리뷰어 동시).
- 같은 파일을 고치는 쓰기 역할은 한 번에 하나만 실행한다.
- 하위 에이전트의 "통과했다" 를 완료 근거로 쓰지 않는다. 메인 세션이 검증 명령을 다시 돌린다.

## 주의

사용자 전역(`~/.claude/agents/`)에 같은 이름의 다른 프로젝트용 에이전트가 있을 수 있다. 이 저장소의 프로젝트 스코프 정의가
우선한다. 역할이 이 저장소 규칙(`com.commercefoundry`, `adapter/in/admin`, `payment-guide.md`)을 모르는 것처럼 굴거나
**hondigagae 경로·여행 도메인 용어가 등장하면 잘못된 정의를 읽고 있는 것이다.**

Cursor 는 `.claude/agents/` 도 하위 에이전트로 읽는다. 같은 이름이면 `.cursor/agents/` 가 이기므로, 동기화 스크립트가
Cursor 전용 정의를 생성해 덮어쓴다 ([ai-agents.md](ai-agents.md)). 역할을 추가·변경하면 `--write` 로 다시 생성한다.
