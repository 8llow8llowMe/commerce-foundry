# Commerce Foundry Agent Guide

Codex와 Cursor가 공유하는 저장소 초기 지침이다. 실제 규칙의 정본은 각 워크스페이스의 `docs/`이며 이 파일은 작업 지도다.

## 프로젝트와 작업 위치

재사용 가능한 쇼핑몰 엔진이다. 첫 고객사 MOKOYURI의 브랜드 규칙을 공통 코드에 고정하지 않고 설정·기능 플래그로 표현한다.
국문 KRW / 영문 USD 스토어프론트와 어드민, 토스페이먼츠 기반 국내 결제·해외 PayPal을 다룬다.

| 경로 | 구성 | 작업 전에 읽을 지침 | 규칙 정본 |
|------|------|--------------------|-----------|
| `backend/` | Java 21 · Spring Cloud MSA · Hexagonal | [backend/AGENTS.md](backend/AGENTS.md) | `backend/docs/*.md` |
| `frontend/` | Next.js · TypeScript · pnpm 모노레포 | [frontend/AGENTS.md](frontend/AGENTS.md) | `frontend/docs/*.md` |

루트에서 시작해도 수정할 워크스페이스의 `AGENTS.md`를 먼저 읽는다. 양쪽을 수정하면 둘 다 읽는다.
Claude Code용 대응 엔트리는 `CLAUDE.md`, `backend/CLAUDE.md`, `frontend/CLAUDE.md`다.

## 공통 규칙

- 공개 저장소다. PG·JWT 키, DB 비밀번호, 고객사 사업자 실정보는 Vault에만 두고 저장소에는 키 이름·환경변수 플레이스홀더만 둔다.
  고객 개인정보·주문번호 원문은 마스킹한다. [secret-management.md](backend/docs/secret-management.md), [키 목록](backend/.env.example)을 따른다.
- 모든 파일은 UTF-8 no BOM, LF로 저장한다(`.bat`·`.cmd`는 CRLF). `.editorconfig`와 `.gitattributes`를 보존한다.
- 신규 기능은 명세(이슈 작업 상세·완료 기준) → 계획 → 구현 → 검증 순서다. 버그는 근본 원인을 확인한 뒤 수정한다.
- 새 규칙은 해당 `docs/*.md`부터 갱신한다. 서비스 책임·API 계약 변경은 관련 문서와 FE 영향을 함께 기록한다.
- 완료 보고 전에 변경 범위에 맞는 검증 명령을 실제 실행한다. 미실행·실패·환경 제약을 통과로 보고하지 않는다.
- 결제·주문·재고 변경은 `payment-guard`와 `order-flow-guard`를 적용한다. 검토 스킬은 코드를 수정하지 않고 발견사항을 보고한다.

## Git 협업

정본: [docs/git-workflow.md](docs/git-workflow.md).

- 이슈 → 브랜치 → 작업 → PR → CI → Rebase and merge → 브랜치 정리 → 이슈 갱신 순서다. 이슈 없이 브랜치를 만들지 않는다.
- 브랜치명은 `<type>/<영역>/<이슈번호>-<영문 kebab 요약>`, 영역은 `be` / `fe` / `infra` / `common`이다.
- 모든 작업 PR의 base는 `develop`이다. `develop`·`main`에 직접 커밋하지 않는다. 동기화는 `rebase`다.
  작업 PR은 Rebase and merge, 릴리스 `develop → main`만 merge commit이다.
- 제목은 `[BE|FE|DOCS|INFRA] feat|fix|chore|refactor|style|docs|test: 요약`, 커밋 제목 끝에는 `(#번호)`를 붙인다.
- 기본 담당자는 BE·INFRA·DOCS `shindonggeun`, FE·프론트 전용 문서/설정 `seonghoho`다. BE API 계약이 바뀌면 FE 리뷰를 요청한다.
- PR은 30파일 / 1,000줄 이내를 목표로 한다. 초과하면 분리 여부를 검토하고 필요한 이유를 본문에 적는다.
- 같은 작업 트리를 다른 세션·사람과 공유한다. `git add -A`, `git add .`, `git stash`를 쓰지 않는다.
  변경 경로를 하나씩 스테이징하고 다른 사람의 변경은 건드리지 않는다.
- 스킬 설정·init 요청만으로 이슈 등록, 브랜치 생성, 커밋·푸시·PR·머지를 실행하지 않는다. 해당 협업 작업을 요청받았을 때 수행한다.

## 스킬과 역할

Codex·Cursor용 스킬은 `.agents/skills/<name>/SKILL.md`에 있다. Codex는 `$스킬명`, Cursor는 `/스킬명`으로 호출한다.
Cursor에서 Claude 호환 스킬이 함께 보이면 이 저장소의 `.agents/skills/` 정의를 사용한다.

| 작업 | 스킬 |
|------|------|
| 이슈 등록·브랜치 시작·PR 생성 | `issue`, `issue-start`, `pr` |
| 작업 분류·역할 선택 | `dev-orchestrator` |
| 백엔드 착수·API·계층 점검 | `backend-feature-bootstrap`, `backend-api-check`, `hexagonal-guard` |
| 결제·주문·재고 검토 | `payment-guard`, `order-flow-guard` |
| 큰 백엔드 작업의 역할 분담 | `backend-multi-agent` |

역할 지침은 `.agents/roles/*.md`, 실행·권한·호출 안내는 [docs/ai-agents.md](docs/ai-agents.md)다.
네이티브 하위 에이전트는 생성본인 Cursor `.cursor/agents/`, Codex `.codex/agents/`(`explorer`·`git_ops`)다.
넓은 탐색은 `explorer`, 여러 단계의 git·gh 작업은 `git-ops`(Codex `git_ops`)에 저비용 모델로 맡기고, 단일 명령은 직접 실행한다.
하위 에이전트를 사용할 수 있고 위임이 허용된 경우에만 위임하며, 지원되지 않으면 메인 세션이 같은 역할을 순차 수행한다.
독립적인 읽기 전용 조사·검토만 병렬화하고 쓰기 역할은 한 번에 하나만 실행한다. 그 외 역할의 모델은 현재 세션 설정을 따른다.
