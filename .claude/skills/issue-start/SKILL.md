---
name: issue-start
description: "commerce-foundry GitHub 이슈 번호로 작업을 시작한다. 이슈를 gh 로 읽어 브랜치명(<type>/<영역>/<번호>-<요약>)을 만들고 최신 origin/develop 에서 분기한 뒤, 이슈의 작업 상세를 작업 계획으로 정리한다. /issue-start 12, '12번 이슈 시작하자', '이슈 브랜치 따줘' 요청이 트리거다."
---

# Issue Start

이슈 하나의 작업을 규칙대로 시작한다.

## Read First

- [docs/git-workflow.md](../../../docs/git-workflow.md) §3 — 브랜치 네이밍, §4-1 — 작업 트리 공유

## 절차

1. **이슈 읽기**

   ```bash
   gh issue view <번호> --json number,title,state,assignees,labels,body,milestone
   ```

   - 닫힌 이슈면 멈추고 알린다.
   - 담당자가 비어 있으면 `/issue` 의 담당자 규칙으로 지정할지 묻는다.

2. **브랜치명 만들기** — `<type>/<영역>/<번호>-<요약>`

   | 제목 | 값 |
   |------|-----|
   | `feat` → `feature`, `fix` → `fix`, 그 외 타입은 그대로 | `type` |
   | `[BE]` → `be`, `[FE]` → `fe`, `[INFRA]` → `infra`, `[DOCS]`·양쪽 → `common` | `영역` |
   | 제목 요약을 영문 kebab-case 2~4단어로 | `요약` |

   예: `[BE] feat: 상품 목록·상세 조회 API 구현` (#12) → `feature/be/12-product-query-api`

3. **작업 트리 확인** — `git status --short`. 내 것이 아닌 변경이 있으면 **건드리지 않고** 알린다.
   `git stash` 로 치우지 않는다.

4. **분기**

   ```bash
   git fetch origin
   git switch -c <브랜치명> origin/develop
   ```

   - `origin/develop` 이 없으면 멈추고 알린다 (develop 브랜치 생성은 사용자 확인 후).
   - 같은 이름의 브랜치가 이미 있으면 새로 만들지 않고 그 브랜치로 전환할지 묻는다.

5. **작업 계획 출력** — 이슈의 `작업 상세 내용` · `완료 기준` 을 그대로 가져와 순서를 매긴다.
   - `[BE]` 이고 새 컨텍스트·서비스면 `/backend-feature-bootstrap` 을 먼저 권한다.
   - 결제·주문·재고 이슈면 `backend/docs/payment-guide.md` · `order-flow-guide.md` 를 읽을 문서로 적는다.
   - 큰 작업이면 `/dev-orchestrator` 로 분류를 권한다.

## 출력

```text
ISSUE START
===========
Issue:   #12 [BE] feat: 상품 목록·상세 조회 API 구현
Branch:  feature/be/12-product-query-api (from origin/develop @ <short sha>)
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

- 커밋·푸시는 하지 않는다. 브랜치 생성과 계획까지만 한다.
- 커밋 제목에는 `(#번호)` 를 붙이도록 안내한다: `[BE] feat: 상품 목록 조회 API 구현 (#12)`.
