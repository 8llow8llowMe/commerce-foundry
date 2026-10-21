---
name: issue-start
description: "Commerce Foundry GitHub 이슈를 읽고 origin/develop 기준 작업 브랜치와 구현 계획을 만든다. 이슈 번호로 작업 시작이나 브랜치 생성을 요청할 때 사용한다."
---

# Issue Start

이슈 하나의 작업을 규칙대로 시작한다. 대화 기록 없이 실행될 수 있으므로 필요한 정보는 인자(이슈 번호, `--worktree`)와 저장소에서만 얻는다.

## Read First

- [docs/git-workflow.md](../../../docs/git-workflow.md) §3 — 브랜치 네이밍, §4-1 — 작업 트리 공유, §7-2 — 워크트리 위치

## 절차

1. **이슈 읽기**

   ```bash
   gh issue view <번호> --json number,title,state,assignees,labels,body,milestone
   ```

   - 닫힌 이슈면 멈추고 알린다.
   - 담당자가 비어 있으면 보고에 적는다. 지정 여부는 호출자가 `/issue` 의 담당자 규칙으로 정한다.

2. **브랜치명 만들기** — `<type>/<영역>/<번호>-<요약>`

   | 제목 | 값 |
   |------|-----|
   | `feat` → `feature`, `fix` → `fix`, 그 외 타입은 그대로 | `type` |
   | `[BE]` → `be`, `[FE]` → `fe`, `[INFRA]` → `infra`, `[DOCS]`·양쪽 → `common` | `영역` |
   | 제목 요약을 영문 kebab-case 2~4단어로 | `요약` |

   예: `[BE] feat: 상품 목록·상세 조회 API 구현` (#12) → `feature/be/12-product-query-api`

3. **작업 트리 확인 · 모드 결정** — `git status --short`
   - 인자에 `--worktree`·`워크트리` 가 있거나 커밋되지 않은 변경이 있으면 **워크트리 모드**다.
     남의 변경을 건드리지 않고 별도 디렉터리에서 시작한다. `git stash` 로 치우지 않는다.
   - 그 외에는 현재 작업 트리에서 분기한다.

4. **분기**

   ```bash
   git fetch origin
   # 기본
   git switch --no-track -c <브랜치명> origin/develop
   # 워크트리 모드 — 메인 저장소 밖 commerce-foundry-wt/<번호>-<요약>
   MAIN=$(dirname "$(git rev-parse --path-format=absolute --git-common-dir)")
   git worktree add --no-track -b <브랜치명> "$MAIN/../commerce-foundry-wt/<번호>-<요약>" origin/develop
   ```

   - **`--no-track` 을 붙인다.** 원격 브랜치에서 분기하면 git 이 upstream 을 `origin/develop` 으로 잡아, 맨 `git push` 가 develop 쪽을 향할 수 있다.
     upstream 은 첫 푸시 `git push -u origin HEAD` 가 같은 이름의 원격 브랜치로 잡는다. 분기 뒤 `git rev-parse --abbrev-ref @{u}` 가 비어 있어야 한다.

   - `origin/develop` 이 없으면 멈추고 알린다 (develop 브랜치 생성은 사용자 확인 후).
   - 같은 이름의 브랜치나 워크트리(`git worktree list`)가 이미 있으면 새로 만들지 않고 멈춰서 보고한다.

5. **작업 계획 출력** — 이슈의 `작업 상세 내용` · `완료 기준` 을 그대로 가져와 순서를 매긴다.
   - `[BE]` 이고 새 컨텍스트·서비스면 `/backend-feature-bootstrap` 을 먼저 권한다.
   - 결제·주문·재고 이슈면 `backend/docs/payment-guide.md` · `order-flow-guide.md` 를 읽을 문서로 적는다.
   - 큰 작업이면 `/dev-orchestrator` 로 분류를 권한다.

## 출력

```text
ISSUE START
===========
Issue:    #12 [BE] feat: 상품 목록·상세 조회 API 구현
Branch:   feature/be/12-product-query-api (from origin/develop @ <short sha>)
Worktree: D:/.../commerce-foundry-wt/12-product-query-api   (워크트리 모드일 때만 — 이후 작업은 이 경로에서)
Assignee: shindonggeun   Labels: backend-product-service, enhancement

Read First:
- backend/docs/api-design-guide.md
- ...

Plan:
1. ...
2. ...

Done When:
- ...
```

## Rules

- 커밋·푸시는 하지 않는다. 브랜치(워크트리) 생성과 계획까지만 한다.
- 커밋 제목에는 `(#번호)` 를 붙이도록 안내한다: `[BE] feat: 상품 목록 조회 API 구현 (#12)`.
