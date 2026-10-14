# Git 협업 워크플로

> 저장소 전체(BE/FE 공통) 기준이다. 엔트리는 루트 `CLAUDE.md`.
> 이 문서가 이슈·브랜치·커밋·PR·머지 규칙의 정본이다.

## 1. 전체 흐름

```text
이슈 생성 → 브랜치 생성 → 작업·커밋 → PR 생성 → CI 통과 → Rebase and merge → 브랜치 삭제 → 이슈 갱신
```

| 단계 | 지킬 것 | 스킬 |
|------|---------|------|
| 이슈 생성 | 화면 / 기능 단위, 담당자·라벨 지정 | `/issue` |
| 브랜치 생성 | 이름에 이슈 번호 포함, `develop` 에서 분기 | `/issue-start` |
| PR 생성 | `Issue Number` · assignee · 라벨 | `/pr` |
| Rebase and merge | `develop` 선형 유지 (ruleset 이 강제), 원격 브랜치 자동 삭제 | |
| **이슈 갱신** | **체크박스 갱신 → 전부 끝났으면 닫기** | |

**이슈 없이 브랜치를 만들지 않는다.** 이슈가 작업 단위이자 추적 지점이다.

### 브랜치 구조

| 브랜치 | 용도 |
|--------|------|
| `main` | 운영 배포본. 릴리스 PR(develop → main)로만 바뀐다 |
| `develop` | 통합 브랜치. 모든 작업 PR 의 base |
| `<type>/<영역>/<번호>-<요약>` | 이슈 하나의 작업 브랜치 |

## 2. 이슈

### 단위

> **화면 / 기능 단위로 쪼갠다.**

| 좋음 | 나쁨 |
|------|------|
| `상품 상세 조회 API 구현` | `상품 DTO 작성` (너무 잘음 — 중간 상태가 develop 에 쌓인다) |
| `어드민 상품 등록·수정 화면` | `어드민 전체 구현` (너무 큼 — PR 이 100파일을 넘는다) |

명세·구현·테스트는 **한 이슈에 묶는다.** 하나의 이슈가 하나의 PR 이 되고, PR 은 **30파일 이내**를 목표로 한다.

### 제목 · 담당자 · 라벨

- 제목: 커밋과 같은 `[영역] type: 요약` 형식
- 템플릿: `.github/ISSUE_TEMPLATE/` (기능 / 버그)

| 영역 | 기본 담당자 (assignee) |
|------|------------------------|
| `[BE]` | `shindonggeun` |
| `[FE]` | `seonghoho` |
| `[INFRA]` / `[DOCS]` | `shindonggeun` (프론트 전용 설정·문서면 `seonghoho`) |

라벨은 **서비스 라벨 + 타입 라벨**을 함께 붙인다.

| 범위 | 서비스 라벨 |
|------|-------------|
| `frontend/` | `frontend-web` |
| `backend/service/<name>-service` | `backend-<name>-service` (`auth` / `product` / `order` / `payment` / `delivery`) |
| `backend/core/**` | `backend-core` |
| 게이트웨이 / 유레카 | `backend-api-gateway` / `backend-service-discovery` |
| 백엔드 문서 | `backend-docs` |

| 타입 | 타입 라벨 |
|------|-----------|
| `feat` | `enhancement` |
| `fix` | `bug` |
| `docs` | `documentation` |

`[INFRA]` / `[DOCS]` 처럼 어느 워크스페이스도 가리키지 않는 변경은 서비스 라벨을 **새로 만들지 않고 생략**한다.

### 마일스톤

| 마일스톤 | 범위 |
|----------|------|
| `v1.0 국내 오픈` | 카페24 대체에 필요한 최소 기능 — 상품·회원·장바구니·주문·국내 결제·배송·어드민 |
| `v1.1 해외 판매` | 영문 스토어·USD·PayPal·해외 배송비 |

## 3. 브랜치

### 네이밍 (필수)

```text
<type>/<영역>/<이슈번호>-<요약>
```

| 자리 | 값 |
|------|-----|
| `type` | `feature` \| `fix` \| `chore` \| `refactor` \| `docs` \| `test` \| `style` |
| `영역` | `be` \| `fe` \| `infra` \| `common` (`[DOCS]` 나 양쪽에 걸치면 `common`) |
| `이슈번호` | GitHub 이슈 번호 |
| `요약` | 영문 kebab-case, 2~4단어 |

```bash
feature/be/12-product-detail-api
feature/fe/20-cart-page
fix/be/31-stock-release
chore/infra/1-repo-bootstrap
```

### 시작

```bash
git fetch origin
git switch -c feature/be/12-product-detail-api origin/develop
```

`/issue-start 12` 가 이 과정을 대신한다.

## 4. 커밋

- 형식: `[영역] type: 요약` — 예: `[BE] feat: 상품 상세 조회 API 구현 (#12)`
- 타입은 `feat` / `fix` / `chore` / `refactor` / `style` / `docs` / `test`
- **의미 단위로 나눈다.** `Rebase and merge` 라서 **커밋이 그대로 develop 에 남는다.**
  "wip", "fix typo" 같은 커밋을 남기지 않는다.
- 커밋 제목 끝에 `(#이슈번호)` 를 붙인다 — `git log` 만으로 이슈가 추적된다.

### 4-1. 작업 트리를 혼자 쓰지 않는다

같은 작업 트리에서 다른 Claude 세션이나 사람이 동시에 파일을 고치고 있을 수 있다.

| 하지 않는다 | 대신 |
|-------------|------|
| `git add -A` · `git add .` | **경로를 하나씩 적는다.** 커밋 전에 `git diff --cached --name-only` 로 확인한다 |
| `git stash` | 남의 진행 중 작업을 통째로 치운다. 중간 상태 검증은 **워크트리**로 한다 |
| 남의 파일에 포매터 실행 | 내 변경이 아닌 파일을 고치지 않는다 |

**커밋 전에 항상 `git status --short` 로 내 것이 아닌 변경이 있는지 먼저 본다.**

### 4-2. 공개 저장소다

`8llow8llowMe/commerce-foundry` 는 public 이다. 아래는 **커밋·이슈·PR 어디에도** 쓰지 않는다.

- PG 시크릿 키, 클라이언트 키(라이브), JWT 서명 키, DB 비밀번호
- 사업자등록번호·정산 계좌 같은 사업자 실정보 (값은 Vault 에 등록 — `backend/docs/secret-management.md`)
- 고객 이름·전화번호·주소·주문번호 원문 (이슈에 적어야 하면 마스킹)
- 카페24 이관 원본 엑셀 (`.gitignore` 로 막혀 있다)

## 5. develop 동기화는 rebase 로 한다

```bash
git fetch origin
git rebase origin/develop
```

`git merge origin/develop` 은 쓰지 않는다. 브랜치에 머지 커밋이 섞이면 `Rebase and merge` 가
히스토리를 예상과 다르게 평탄화한다.

## 6. PR

### 생성

```bash
git push -u origin feature/be/12-product-detail-api
gh pr create --base develop \
  --title "[BE] feat: 상품 상세 조회 API 구현" \
  --body-file <본문> \
  --assignee @me \
  --label backend-product-service --label enhancement
```

- 본문은 `.github/PULL_REQUEST_TEMPLATE.md` 를 채운다. `/pr` 스킬을 쓰면 된다.
- **`Issue Number: #12` 를 반드시 채운다.**
- 제목은 이슈 제목과 같게 둔다.
- **assignee 와 라벨을 생성 시점에 함께 지정한다.** 나중에 붙이려고 미루지 않는다.
- 여러 영역에 걸치면 라벨을 **모두** 붙인다. `.github/workflows/label.yml` 이 경로로 서비스 라벨을
  자동으로 더하지만, 그래도 생성 시점에 확인한다.
- **30파일 / 1,000줄을 넘으면** 쪼갤 수 있는지 먼저 보고, 넘겨야 하면 이유를 본문에 적는다.

### 리뷰

- 2인 팀이라 셀프 머지를 허용한다. 단 **PR 은 생략하지 않는다.**
- **API 계약이 바뀌는 [BE] PR 은 `seonghoho` 를 리뷰어로 지정한다** — 프론트가 깨지는 변경을 머지 전에 알린다.
- 결제·주문·재고를 건드린 PR 은 `/payment-guard` · `/order-flow-guard` 결과를 본문 `참고 사항` 에 남긴다.

## 7. 머지 · 이슈 갱신 · 릴리스

- 작업 PR 의 머지는 **`Rebase and merge`** 만 쓴다: `gh pr merge <번호> --rebase`
- **CI 통과가 머지 조건이다.**
- 머지되면 **원격 브랜치는 GitHub 가 자동으로 지운다** (아래 저장소 설정). 남는 일은 로컬 정리다 — 아래 §7-2.
- 머지 뒤 이슈 체크박스를 갱신하고, 전부 끝났으면 이슈를 닫는다. `develop` 머지는 `Closes #N` 으로
  자동으로 닫히지 않는다 (기본 브랜치가 `main` 이라서) — `gh issue close <번호>` 로 직접 닫는다.
- **릴리스(develop → main)는 `Create a merge commit`** 을 쓴다: `gh pr merge <번호> --merge`.
  `develop` 은 ruleset 으로 삭제가 막혀 있어 자동 삭제 대상이 아니다.

### 7-1. 저장소 설정

GitHub 에 적용돼 있는 값이다. 바꾸면 이 표도 같이 고친다 (`gh api repos/8llow8llowMe/commerce-foundry`, `.../rulesets`).

| 설정 | 값 | 이유 |
|------|-----|------|
| Allow rebase merging | 켬 | 작업 PR 의 유일한 머지 방식. 커밋이 그대로 develop 에 남는다 |
| Allow merge commits | 켬 | **릴리스(develop → main) 전용.** 작업 PR 에는 아래 ruleset 이 막는다 |
| Allow squash merging | 끔 | 의미 단위로 나눈 커밋을 하나로 뭉갠다 |
| Automatically delete head branches | 켬 | 머지된 원격 작업 브랜치를 남기지 않는다 |
| Ruleset `develop-linear-history` | `develop` 에 선형 이력 강제 · 삭제 금지 | 작업 PR 이 merge commit 으로 들어오는 것을 막는다 |

### 7-2. 로컬 정리

```bash
git fetch --prune origin
git switch develop && git merge --ff-only origin/develop
git worktree remove <워크트리 경로>    # 워크트리에서 작업했다면 — 브랜치를 쥐고 있어 먼저 지운다
git branch -D <머지된 브랜치>          # 리베이스 머지라 SHA 가 달라 -d 는 거부된다
```

- `-D` 전에 `git diff <브랜치> origin/develop` 이 비었거나 `git cherry -v origin/develop <브랜치>` 가 전부 `-` 인지
  (= 이미 develop 에 있음) 확인한다.
- 워크트리는 저장소 밖 `../commerce-foundry-wt/<번호>-<요약>` 에 만든다. 저장소 안에 두면 빌드 산출물·`.gitignore` 와 섞인다.
- Windows 에서 `git worktree remove` 가 `Filename too long` 으로 실패하면 **등록은 풀리고 폴더만 남는다** (Gradle 빌드 산출물의 긴 경로).
  위 diff 확인을 마친 뒤 PowerShell `Remove-Item -LiteralPath '\\?\<절대 경로>' -Recurse -Force` 로 남은 폴더를 지우고
  `git worktree prune` 한다. 다시 겪지 않으려면 `git config --global core.longpaths true` 를 켠다.
