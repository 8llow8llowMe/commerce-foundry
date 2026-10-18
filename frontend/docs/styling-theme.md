# Styling & Theme Guide

## 1. 토큰 두 층

| 층 | 파일 | 하는 일 |
|----|------|---------|
| 중립 토큰 | `packages/ui/src/tokens.css` | `--cf-*` 원시 토큰 기본값 + Tailwind `@theme inline` 연결 |
| 브랜드 테마 | `packages/theme-<brand>/theme.css` | `--cf-*` 값만 덮어쓴다 |

사용 가능한 클래스: `bg-bg` `text-fg` `bg-muted` `text-muted-fg` `border-border` `bg-primary` `text-primary-fg` `text-danger` `border-danger` `ring-primary` `rounded-md` `font-sans`.
새 토큰이 필요하면 `tokens.css` 에 `--cf-*` 와 `@theme` 연결을 같이 더하고 이 목록을 갱신한다.

## 2. 규칙

- 임의 색 값(`bg-[#fff]`, `text-[rgb(...)]`)을 쓰지 않는다. ESLint `no-restricted-syntax` 가 막는다.
- 앱 코드에 브랜드 이름·색을 하드코딩하지 않는다. 이름은 `brand.name`(`@cf/brand`).
- 어드민은 브랜드 테마 CSS 를 import 하지 않는다(중립 토큰).
- 폰트: 테마가 `--cf-font-sans` 를 정한다. 웹폰트를 로딩해야 하면 앱 `layout.tsx` 에서 `next/font` 로 하고 변수를 연결한다.
- 다크 모드는 아직 없다.

## 3. 메뉴 (navigation)

헤더 메뉴는 컴포넌트가 아니라 브랜드 설정 `brand.navigation`(`NavItem[]`, 타입은 `@cf/ui`)이 갖는다. 사업자마다 메뉴가 다르기 때문이다.

| 필드 | 쓰는 곳 |
|------|---------|
| `label` · `description` · `href` | 전체 메뉴 패널 — 대제목 + 한 줄 설명 (2열, 모바일 1열 전체 화면) |
| `primary: true` | PC(`lg` 이상) 헤더의 로고 아래 1차 메뉴 |
| `shortcut: true` | 모바일 헤더의 바로가기 줄 — 첫 화면에서 핵심 목록으로 가는 길 |

- 순서는 배열 순서다. 메뉴를 바꿀 때 `apps/storefront/src/components/header/*` 를 고치지 않는다.
- 아직 없는 화면도 링크로 둘 수 있다. 앱의 `not-found.tsx` 가 "준비 중" 문구로 받는다(상태는 404).

## 4. 고객사 정보

사업자등록번호·대표자 등 실정보는 테마에 넣지 않는다(공개 저장소). 서버 env(`BUSINESS_*`)로 받는다.

## 5. 새 브랜드 추가

1. `packages/theme-mokoyuri` 를 복사해 `packages/theme-<brand>` 를 만들고 이름·`theme.css` 값·`brand.ts`(메뉴 `navigation` 포함)를 바꾼다.
2. 앱 `package.json` 의 `"@cf/brand": "workspace:@cf/theme-<brand>@*"` 한 줄을 바꾼다.
3. `pnpm install && pnpm build`.

## 6. 현재 MOKOYURI 팔레트

임시 값이다(한지 아이보리 배경 · 쪽빛 primary). 브랜드 가이드를 받으면 `theme.css` 값만 바꾼다.
