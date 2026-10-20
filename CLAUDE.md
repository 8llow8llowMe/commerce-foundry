# Commerce Foundry Claude Guide

## 목적

- 저장소 **루트**에서 작업을 시작할 때 Claude Code 가 먼저 읽는 엔트리다.
- 실제 규칙의 정본은 각 워크스페이스의 `docs/` 다. 이 문서는 지도 역할만 한다.

## 서비스 개요

**재사용 가능한 쇼핑몰 엔진**이다. 첫 고객사는 생활한복 브랜드 **MOKOYURI**(카페24 → 자체 쇼핑몰 이전)이고,
같은 코드로 다른 사업자 쇼핑몰도 만들 수 있게 브랜드 특화 규칙을 공통 코드에 고정하지 않는다.

- 고객 스토어프론트(국문 KRW / 영문 USD) + 어드민 콘솔(상품·주문·배송 관리)
- 결제: 토스페이먼츠 통합 — 국내 카드·카카오페이·네이버페이, 해외 PayPal(USD, 별도 계약)

## 워크스페이스

| 경로 | 대상 | 담당 | 엔트리 문서 | 규칙 정본 |
|------|------|------|-------------|-----------|
| `backend/` | Spring Cloud MSA + Hexagonal | `shindonggeun` | `backend/CLAUDE.md` | `backend/docs/*.md` |
| `frontend/` | Next.js 모노레포 (스토어프론트 · 어드민) | `seonghoho` | `frontend/CLAUDE.md` | `frontend/docs/*.md` |

**작업 시작 시 해당 워크스페이스의 엔트리 문서를 먼저 읽는다.** 두 워크스페이스에 걸친 작업이면 양쪽 다 읽는다.

## 공통 규칙

### 공개 저장소다 (필수)

`8llow8llowMe/commerce-foundry` 는 **public** 이다. 코드·설정·이슈·PR·커밋 메시지 어디에도 아래를 쓰지 않는다.

- PG 시크릿 키, JWT 서명 키, DB 비밀번호 → **Vault** 에 등록하고 기동 시 주입
- 사업자등록번호·대표자·통신판매업 신고번호 등 고객사 실정보 → **Vault** 에 등록
- 고객 개인정보·주문번호 원문 → 마스킹

저장소에는 키 **이름**만 둔다 — 목록 [`backend/.env.example`](backend/.env.example), 규칙 [`backend/docs/secret-management.md`](backend/docs/secret-management.md).

### 파일 인코딩 (필수)

- 모든 소스 / 설정 / 문서 파일은 **`UTF-8` (no BOM)** 으로 저장한다. Windows 기본 CP949 로 저장하면 한글이 깨진다.
- `.editorconfig`(`charset = utf-8`), `.gitattributes`(`working-tree-encoding=UTF-8`) 설정을 덮어쓰지 않는다.
- `git status` 에서 수정한 적 없는데 diff 가 잡히면 인코딩 문제를 의심한다.

### 커밋 / PR / 이슈 prefix

| prefix | 범위 | 기본 담당 |
|--------|------|-----------|
| `[BE]` | `backend/` 런타임 코드 | `shindonggeun` |
| `[FE]` | `frontend/` 런타임 코드 | `seonghoho` |
| `[DOCS]` | 문서만 변경 | `shindonggeun` |
| `[INFRA]` | 빌드·CI·Claude 설정·저장소 공통 설정 | `shindonggeun` |

형식: `[BE] feat: 상품 상세 조회 API 구현` — 타입은 `feat` / `fix` / `chore` / `refactor` / `style` / `docs` / `test`.

### Git 협업 워크플로 (필수)

**정본: [docs/git-workflow.md](docs/git-workflow.md)**

```text
이슈(/issue) → 브랜치(/issue-start) → 작업 → PR(/pr) → CI 통과 → Rebase and merge → 브랜치 삭제 → 이슈 갱신
```

- **이슈 없이 브랜치를 만들지 않는다.** 이슈는 **화면/기능 단위**로 쪼갠다.
- 브랜치명: `<type>/<영역>/<이슈번호>-<요약>` (예: `feature/be/12-product-detail-api`), 영역은 `be` / `fe` / `infra` / `common`
- 모든 작업 PR 의 base 는 **`develop`** 이다. `develop` · `main` 에 직접 커밋하지 않는다.
- develop 동기화는 `merge` 가 아니라 **`rebase`**. 머지는 **`Rebase and merge`** 만 쓴다 (릴리스 develop → main 만 merge commit).
- PR 은 **30파일 이내**를 목표로 하고, 넘으면 이유를 본문에 적는다.
- **작업 트리를 다른 세션·사람과 동시에 쓴다.** `git add -A` / `git add .` / `git stash` 를 쓰지 않고 경로를 하나씩 스테이징한다.

### 작업 원칙

- 신규 기능은 **명세(이슈 본문의 작업 상세·완료 기준) → 계획 → 구현 → 검증** 순서로 간다.
- 버그는 근본 원인 없이 고치지 않는다.
- **완료 보고 전에 검증 명령을 실제로 돌린다.** 돌리지 않은 것을 통과했다고 하지 않는다.
- 코드 변경과 문서 변경은 같이 움직인다. 새 규칙이 생기면 엔트리보다 해당 `docs/*.md` 를 먼저 갱신한다.
- 결제·주문·재고는 실수 비용이 크다. 해당 코드를 건드리면 `/payment-guard` · `/order-flow-guard` 를 돌린다.

## 스킬 / 에이전트

스킬은 `.claude/skills/*`, 역할별 하위 에이전트는 `.claude/agents/*` 다. Claude Code 에서 `/스킬명` 으로 호출한다.

| 구분 | 스킬 |
|------|------|
| 협업 (공통) | `issue` (gh 로 이슈 자동 등록·담당자 지정), `issue-start` (이슈 → 브랜치), `pr` (gh 로 PR 생성) |
| 개발 오케스트레이션 | `dev-orchestrator` (작업 분류 → 필요한 역할만 호출) |
| 백엔드 착수 · 점검 | `backend-feature-bootstrap`, `backend-api-check`, `hexagonal-guard` |
| 커머스 도메인 점검 | `payment-guard`, `order-flow-guard` |
| 백엔드 멀티 에이전트 | `backend-multi-agent` |

역할·모델·권한의 정본은 [docs/claude-agents.md](docs/claude-agents.md) 다.

| 구분 | 역할 |
|------|------|
| 공용 | `explorer`, `git-ops`, `architect`, `reviewer` |
| 백엔드 | `be-executor`, `be-hexagonal-reviewer`, `be-db-reviewer`, `be-security-reviewer`, `be-payment-reviewer` |
| 프론트엔드 | `fe-implementer`, `fe-reviewer` |

- 검토 역할은 `tools` 로 읽기 전용을 강제한다. Write/Edit 를 부여하지 않는다.
- **넓은 탐색은 `explorer`, 여러 단계의 git·gh 작업은 `git-ops`(둘 다 Haiku)로 내린다.** `/issue-start`·`/pr` 은 `git-ops` 로 포크되므로
  `/pr` 에는 검증 결과를 인자로 넘긴다. 단일 명령·위치를 아는 파일 읽기는 메인 세션이 직접 한다.
- **모든 작업을 병렬화하지 않는다.** 독립적인 읽기 전용 조사·검토만 병렬로 부르고, 쓰기 역할은 한 번에 하나만 실행한다.
- 프론트엔드 에이전트는 `fe-*` 접두어다. 프론트 전용 스킬은 반복 작업이 생기면 `fe-*` 로 추가한다.
