# #586 작업 인계 (2026-11-05 17:50, 회사 → 집)

> PR 을 올리기 전에 이 파일을 지운다 (`git rm HANDOFF-586.md` 후 커밋).

## 상태

- 브랜치: `feature/be/586-product-admin-permissions` (origin/develop `84eee730` 기준)
- 구현 · 테스트 · 문서는 끝났다. **남은 일은 develop 따라잡기(rebase) · 전체 모듈 check · 검토 · PR 이다.**
- 커밋 3개
  1. `[BE] feat` — product-service 어드민 컨트롤러 13개 · 핸들러 53개를 `hasRole('ADMIN')` 에서 `AdminAuthority.*` 로 바꿨다. main 의 `hasRole` 은 0건이다.
  2. `[BE] test` — 역할별 200 · 403 슬라이스 테스트를 넣었다. 신규 `MediaImageUploadAdminControllerTest` · `StockAlertAdminControllerTest` 와, 전체 핸들러 매핑을 대조하는 `ProductAdminAuthorizationMappingTest` 를 추가했다.
  3. `[DOCS] docs` — security-guide §3-1 에 product 표를 넣고, api-design-guide 등 문서 7개의 product 어드민 인가 표기를 고쳤다.

## 매핑 (이슈 #586 댓글에도 있음)

| 기능 | 대상 | 조회 / 쓰기 |
|------|------|-------------|
| CATALOG | 상품 · 카테고리 · 사이즈표 · 상품 초안 · 미디어 업로드 · 메인 전시 · 룩 · 전시 미리보기(MANAGE) · SKU 구조(옵션 · SKU 조합 · 옵션 은퇴) | `CATALOG_READ` / `CATALOG_MANAGE` |
| INVENTORY | 재고 · 재고 알림 | `INVENTORY_READ` / `INVENTORY_MANAGE` |
| COST | 원가 | `COST_READ` / `COST_MANAGE` |

- SKU 를 `CATALOG` 로 둔 이유: 이 핸들러들은 상품 구조를 바꾼다. 이슈 초안은 INVENTORY 였다. 지금 역할표로는 어느 쪽이든 ADMIN · OWNER 만 통과한다.
- multipart 점검: 미디어 업로드는 presigned PUT 이라 product-service 가 multipart 를 받지 않는다. 그래서 해당 사항이 없다.

## 실행한 검증

- 에이전트(회사): `./gradlew :service:product-service:cleanTest :service:product-service:check --no-build-cache` 결과 BUILD SUCCESSFUL. 실행 2036건, 실패 · 오류 0, 스킵 226건(Docker 없음 → Testcontainers).
- 메인 세션이 직접: 바꾼 테스트 9개 클래스(`*ProductAdminAuthorizationMappingTest` · `*MediaImageUploadAdminControllerTest` · `*StockAlertAdminControllerTest` · `*ProductAdminControllerTest` · `*CategoryAdminControllerTest` · `*StockAdminControllerTest` · `*SkuCostAdminControllerTest` · `*DisplayAdminControllerTest` · `*LookAdminControllerTest`)를 돌렸다. 353건 통과, 실패 · 스킵 0.

## 집에서 할 일

1. `git fetch origin && git rebase origin/develop`. product 어드민 컨트롤러를 바꾼 새 커밋이 있으면, 그 핸들러도 `AdminAuthority` 로 바꾸고 `ProductAdminAuthorizationMappingTest` 표에 추가한다.
2. `./gradlew :service:product-service:cleanTest :service:product-service:check --no-build-cache` (루트 check 는 돌리지 않는다. `--stop` 은 쓰지 않는다)
3. 검토(T2, 인가 신호 1개): `be-security-reviewer` + `reviewer` 를 병렬로 돌린다. CRITICAL · HIGH 가 나오면 고친다.
4. 이 파일을 지우는 커밋 → `/pr 검증: product-service check 통과 · 검토 결과` (Closes #586)
5. 머지 후 이슈 체크박스를 갱신하고 워크트리 · 브랜치를 정리한다.

## 남은 우려 · 후속

- FE: MANAGER · STAFF 계정 발급(#237) 때, 상품 · 재고 화면의 쓰기 버튼이 403 을 받는 경우를 처리해야 한다.
- `done-checklist.md` · security-guide §5 의 "전환 전 `hasRole('ADMIN')`" 일반 문구는 order · payment · delivery 등이 아직 쓰고 있어 그대로 두었다(#587 · #588 에서 정리).
