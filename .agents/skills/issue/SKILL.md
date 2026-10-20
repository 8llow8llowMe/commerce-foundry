---
name: issue
description: "Commerce Foundry GitHub 이슈를 한국어로 작성·등록한다. 이슈 생성·분할 요청에 담당자, 서비스·타입 라벨, 마일스톤을 적용한다. 초안만 요청하면 등록하지 않는다."
---

# Issue

commerce-foundry 이슈를 작성하고 **gh 로 바로 등록**한다.

## Read First

- [docs/git-workflow.md](../../../docs/git-workflow.md) §2 — 이슈 단위, 담당자, 라벨, 마일스톤

## 모드

| 요청 | 동작 |
|------|------|
| 기본 (`/issue ...`, "이슈 등록해줘") | 작성 → **바로 등록** → 결과 표 출력 |
| "초안만", "등록하지 말고", `--draft` | 작성한 제목·본문만 출력하고 등록하지 않는다 |
| 큰 기능 ("결제 기능 이슈 쪼개줘") | 화면/기능 단위로 나눈 목록을 먼저 표로 보여주고 등록한다 |

## 1. 쪼개기

- **화면 / 기능 단위**로 쪼갠다. 하나의 이슈 = 하나의 PR = **30파일 이내**.
- 명세·구현·테스트를 한 이슈에 묶는다. "DTO 작성" 처럼 단계로 쪼개지 않는다.
- 백엔드와 프론트가 같은 기능을 다루면 **[BE] 이슈와 [FE] 이슈를 따로** 만들고 본문에서 서로 참조한다.

## 2. 제목

`[영역] type: 요약` — 영역은 `BE` / `FE` / `INFRA` / `DOCS`, 타입은 `feat` / `fix` / `chore` / `refactor` / `docs` / `test` / `style`.

예: `[BE] feat: 상품 목록·상세 조회 API 구현`, `[FE] feat: 장바구니 화면 구현`, `[INFRA] chore: 백엔드 CI 워크플로 추가`

## 3. 담당자 (assignee)

| 영역 | assignee |
|------|----------|
| `[BE]` | `shindonggeun` |
| `[FE]` | `seonghoho` |
| `[INFRA]` · `[DOCS]` | `shindonggeun` (프론트 전용 설정·문서면 `seonghoho`) |

사용자가 담당자를 직접 말하면 그것이 우선한다.

## 4. 라벨

**서비스 라벨 + 타입 라벨**을 함께 붙인다.

| 범위 | 서비스 라벨 |
|------|-------------|
| `[FE]` | `frontend-web` |
| auth / product / order / payment / delivery 서비스 | `backend-<name>-service` |
| `backend/core/**` | `backend-core` |
| 게이트웨이 / 유레카 | `backend-api-gateway` / `backend-service-discovery` |
| 백엔드 문서만 | `backend-docs` |
| `[INFRA]` · 루트 `[DOCS]` | 서비스 라벨 없음 (새로 만들지 않는다) |

| 타입 | 타입 라벨 |
|------|-----------|
| `feat` | `enhancement` |
| `fix` | `bug` |
| `docs` | `documentation` |
| 그 외 | 없음 |

여러 서비스에 걸치면 서비스 라벨을 **모두** 붙인다. 표에 있는데 저장소에 없는 라벨은
`gh label create <이름> --color <색> --description <설명>` 로 만든다. **표에 없는 라벨은 만들지 않는다.**

## 5. 마일스톤

| 마일스톤 | 대상 |
|----------|------|
| `v1.0 국내 오픈` | 상품·회원·장바구니·주문·국내 결제·배송·어드민 |
| `v1.1 해외 판매` | 영문 스토어·USD·PayPal·해외 배송 |

어느 쪽인지 분명하지 않으면 붙이지 않는다.

## 6. 본문 템플릿

```markdown
## 어떤 기능인가요? ✏

- [추가하려는 기능을 1~3줄로]

## 작업 상세 내용 📝

- [ ] [해야 할 일]
- [ ] [해야 할 일]
- [ ] [해야 할 일]

## 완료 기준 ✅

- [ ] [끝났다고 말할 수 있는 조건 — API 응답, 화면 동작, 테스트]

## 영향 범위(선택)

- BE 서비스: ...
- FE 화면: ...

## 참고할만한 자료(선택)

- [관련 문서: backend/docs/..., 관련 이슈: #N]
```

- 할 일 체크박스는 2~6개. **[BE] 이슈면 API 초안**(메서드·경로·인증)을 작업 상세에 적는다.
- 버그는 `.github/ISSUE_TEMPLATE/bug-issue.md` 구조(어떤 버그 / 재현 / 예상·실제 / 영향도)를 따른다.

## 7. 등록 절차

1. **중복 확인** — `gh issue list --state all --search "<핵심 키워드> in:title" --limit 10`. 같은 일이 있으면 등록하지 않고 알린다.
2. **본문 파일 저장** — 파일 편집 도구로 본문을 임시 파일(운영체제 임시 디렉터리)에 저장한다. 셸 문자열로 넘기면 한글·백틱·`$` 가 깨진다.
3. **등록**

   ```bash
   gh issue create \
     --title "[BE] feat: 상품 목록·상세 조회 API 구현" \
     --body-file <본문 파일> \
     --assignee shindonggeun \
     --label backend-product-service --label enhancement \
     --milestone "v1.0 국내 오픈"
   ```

4. 여러 건이면 **선후 관계 순서대로** 등록하고, 뒤 이슈 본문의 `참고` 에 앞 이슈 번호를 적는다
   (먼저 등록해 번호를 받은 뒤 `gh issue edit <번호> --body-file` 로 보정해도 된다).
5. 등록 실패(권한, 없는 라벨·마일스톤)는 숨기지 않고 그대로 보고한다.

## 8. 출력

```text
| # | 제목 | 담당 | 라벨 | 브랜치 제안 |
|---|------|------|------|-------------|
| 12 | [BE] feat: 상품 목록·상세 조회 API 구현 | shindonggeun | backend-product-service, enhancement | feature/be/12-product-query-api |
```

브랜치 제안은 `<type>/<영역>/<번호>-<영문 kebab 2~4단어>` 다 (`feat` → `feature`, 영역 `[DOCS]` → `common`).
작업을 바로 시작하려면 `/issue-start <번호>` 를 안내한다.

## Rules

- 한국어로 쓴다.
- **공개 저장소다.** 시크릿 키, 사업자등록번호, 고객 개인정보, 주문번호 원문을 본문에 쓰지 않는다.
- 범위가 너무 크면 등록 전에 쪼개는 안을 먼저 보여준다.
