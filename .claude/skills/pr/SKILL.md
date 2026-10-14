---
name: pr
description: "commerce-foundry Pull Request 를 한국어 템플릿으로 작성하고 gh 로 develop 대상 PR 을 만든다. 브랜치명에서 이슈 번호를 읽어 Issue Number 를 채우고, 변경 경로로 서비스 라벨을, assignee 는 @me 로 지정한다. /pr 요청, PR body, pull request 생성, '[BE] feat: ... PR 올려줘' 가 트리거다."
---

# PR

commerce-foundry PR 본문을 작성하고 **gh 로 생성**한다.

## Read First

- [docs/git-workflow.md](../../../docs/git-workflow.md) §6~7 — PR 규칙, 리뷰, 머지 방식
- [.github/PULL_REQUEST_TEMPLATE.md](../../../.github/PULL_REQUEST_TEMPLATE.md)

## 모드

| 요청 | 동작 |
|------|------|
| 기본 (`/pr`) | 본문 작성 → 푸시 → **PR 생성** → URL 출력 |
| "초안만", `--draft-body` | 제목·본문만 출력 |
| "드래프트 PR" | `gh pr create --draft` |

## 절차

1. **변경 범위 확인**

   ```bash
   git status --short
   git fetch origin
   git log --oneline origin/develop..HEAD
   git diff --stat origin/develop...HEAD
   git diff --name-only origin/develop...HEAD
   ```

   - 커밋되지 않은 변경이 있으면 PR 에 포함되지 않는다고 알린다. 남의 변경일 수 있으니 대신 커밋하지 않는다.
   - **30파일 / 1,000줄을 넘으면** 쪼갤 수 있는지 먼저 제안하고, 그대로 가면 이유를 `참고 사항` 에 적는다.

2. **이슈 번호** — 브랜치명 `<type>/<영역>/<번호>-<요약>` 의 번호를 쓴다. `gh issue view <번호> --json title` 로
   제목을 가져와 **PR 제목을 이슈 제목과 같게** 둔다. 번호를 못 찾으면 사용자에게 묻는다 — `Issue Number: #` 를 비워 두지 않는다.

3. **라벨** — 변경 경로로 정한다 (`.github/labeler.yml` 과 같은 표).

   | 경로 | 라벨 |
   |------|------|
   | `frontend/**` | `frontend-web` |
   | `backend/service/<name>-service/**` | `backend-<name>-service` |
   | `backend/core/**` | `backend-core` (+ 영향받는 서비스 라벨을 직접 더한다) |
   | `backend/cloud/api-gateway/**` / `service-discovery/**` | `backend-api-gateway` / `backend-service-discovery` |
   | `backend/docs/**` | `backend-docs` |

   타입 라벨도 붙인다: `feat` → `enhancement`, `fix` → `bug`, `docs` → `documentation`.
   `[INFRA]` · 루트 `[DOCS]` 는 서비스 라벨을 생략한다.

4. **리뷰어** — `[BE]` PR 이 API 계약(경로·요청/응답 필드·오류 코드)을 바꾸면 `--reviewer seonghoho` 를 붙인다.

5. **본문 작성** — 템플릿을 채워 Write 도구로 임시 파일에 저장한다.
   - 실제 확인하지 않은 체크박스는 체크하지 않는다.
   - `검증 내역` 에는 **실제로 돌린 명령과 결과**만 적는다. 미실행·실패를 숨기지 않는다.
   - 결제·주문·재고를 건드렸으면 `/payment-guard` · `/order-flow-guard` 결과 요약을 `참고 사항` 에 적는다.

6. **푸시 · 생성**

   ```bash
   git push -u origin HEAD
   gh pr create --base develop \
     --title "[BE] feat: 상품 목록·상세 조회 API 구현" \
     --body-file <본문 파일> \
     --assignee @me \
     --label backend-product-service --label enhancement
   ```

   이미 만든 PR 은 `gh pr edit <번호> --add-assignee @me --add-label <라벨>` 로 보정한다.

7. **출력** — PR URL, 붙인 라벨, 리뷰어, 그리고 머지 방법을 안내한다:
   `gh pr merge <번호> --rebase` (CI 통과 후). 원격 브랜치는 GitHub 가 자동 삭제한다 — 로컬 브랜치·워크트리 정리는
   `docs/git-workflow.md` §7-2. 머지 뒤 이슈 체크박스 갱신 · `gh issue close <번호>`.

## 본문 템플릿

```markdown
## 📝 작업 내용

[작업 내용을 2~4문장으로 요약]

### 주요 변경 사항

1. [주요 변경 1]
2. [주요 변경 2]
3. [주요 변경 3]

## 타입

- [ ] feat: 새로운 기능 추가
- [ ] fix: 버그 수정
- [ ] chore: 빌드 업무 수정, 패키지 매니저 수정
- [ ] refactor: 코드 리펙토링
- [ ] style: 코드 포맷팅, 세미콜론 누락, 코드 변경이 없는 경우
- [ ] docs: 문서 수정
- [ ] test: 테스트 코드, 리펙토링 테스트 코드 추가

## PR 하기 전에 확인해주세요

- [ ] 코딩 컨벤션을 지켰나요?
- [ ] local ci test를 진행하셨나요?
- [ ] 완료 체크리스트를 확인하셨나요?
- [ ] 결제·주문·재고 로직을 건드렸다면 `/payment-guard` · `/order-flow-guard` 를 돌렸나요?
- [ ] 비밀키·사업자 정보·고객 개인정보가 커밋에 섞이지 않았나요?

## 검증 내역

- [실제로 돌린 명령과 결과]

## 참고 사항

- [리뷰어가 알아야 할 점 — API 계약 변경, FE 영향, 마이그레이션]

## 연관된 이슈

Issue Number: #
```

## Rules

- 한국어로 쓴다. 기능 단위로 묶고 파일 나열식 changelog 를 피한다.
- 제목 prefix 는 변경 범위에 맞춘다: `backend/` → `[BE]`, `frontend/` → `[FE]`, 문서만 → `[DOCS]`, 빌드·CI·Claude 설정 → `[INFRA]`.
- 백엔드 검증 명령: `./gradlew compileJava`, `./gradlew :service:<svc>:cleanTest :service:<svc>:test --no-build-cache`, `./gradlew check`.
- secret, token, private key, password, 사업자 실정보는 포함하지 않는다.
