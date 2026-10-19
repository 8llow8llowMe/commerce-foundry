# Styling & Theme Guide

## 1. 토큰 두 층

| 층 | 파일 | 하는 일 |
|----|------|---------|
| 중립 토큰 | `packages/ui/src/tokens.css` | `--cf-*` 원시 토큰 기본값 + Tailwind `@theme inline` 연결 |
| 브랜드 테마 | `packages/theme-<brand>/theme.css` | `--cf-*` 값만 덮어쓴다 |

사용 가능한 클래스: `bg-bg` `text-fg` `bg-muted` `text-muted-fg` `border-border` `bg-primary` `text-primary-fg` `text-danger` `border-danger` `ring-primary` `bg-accent` `text-accent-fg` `rounded-md` `font-sans` `font-display` `font-serif`.

| 글꼴 클래스 | 토큰 | 용도 |
|-------------|------|------|
| `font-sans` | `--cf-font-sans` | 본문 · 메뉴 · 상품명 · 가격 (기본) |
| `font-display` | `--cf-font-display` | 영문 제목 · 워드마크 |
| `font-serif` | `--cf-font-serif` | 한글 감성 문구 |

`bg-accent` 는 히어로처럼 **일부 구간에만** 쓰는 강조 배경이다. 사이트 전체 배경으로 쓰지 않는다.
새 토큰이 필요하면 `tokens.css` 에 `--cf-*` 와 `@theme` 연결을 같이 더하고 이 목록을 갱신한다.

## 2. 규칙

- 임의 색 값(`bg-[#fff]`, `text-[rgb(...)]`)을 쓰지 않는다. ESLint `no-restricted-syntax` 가 막는다.
- 앱 코드에 브랜드 이름·색을 하드코딩하지 않는다. 이름은 `brand.name`(`@cf/brand`).
- 어드민은 브랜드 테마 CSS 를 import 하지 않는다(중립 토큰).
- 폰트: 테마가 `--cf-font-*` 를 정하고 **웹폰트도 테마가 로딩한다** — `theme.css` 첫머리에서 npm 폰트 패키지의 CSS 를 `@import` 한다(self-host, 버전은 `pnpm-workspace.yaml` catalog).
  앱 코드(`layout.tsx`)는 브랜드 글꼴을 모른다. 그래서 브랜드를 바꿔도 앱을 고치지 않는다.
  한글 글꼴은 unicode-range 로 나뉜 CSS(`*-dynamic-subset.css`, Fontsource 의 굵기별 CSS)를 써서 쓰는 글자 구간만 내려받게 한다. 굵기도 쓰는 것만 import 한다.
  `next/font` 를 쓰지 않으므로 preload · 폴백 크기 보정은 없다(`font-display: swap`).
- 다크 모드는 아직 없다.
- 가장 작은 글자 `text-xs` 는 토큰 `--cf-text-xs` 다(중립 12px). 브랜드가 최소 글자 크기를 정하면 테마가 덮어쓴다.

### 2-1. 디자인 금지 목록 (#57 §5, #118)

스토어프론트 화면이 함께 지키는 규칙이다. 섹션 레이아웃 명세는 #59 · #60 댓글이다.

- 제목 위 작은 라벨(eyebrow)을 두지 않는다 — `HomeSection` 타입에도 없다. 필요한 정보는 제목 · 첫 문장 · 버튼 문구에 넣는다.
- `01 / 02 / 03` 번호 매기기를 하지 않는다.
- 장식용 가는 구분선 · 1px 테두리 박스를 쓰지 않는다. 구분은 **여백**이나 **배경 톤 한 단계**(`bg-muted` · `bg-accent`)로 한다.
  예외: 입력 · 선택 컨트롤(옵션 버튼 · 수량 · 입력칸)의 테두리, 콘텐츠 위에 떠 있는 층(고정 헤더 · 모바일 하단 구매 바)의 경계선, 진짜 표(`<table>`).
- 버튼은 한 모양 — 직각 + 단색 채움. 주 버튼 `primary`(Soft Black), 보조 버튼 `secondary`(accent 채움, 테두리 없음).
- 판매 상태(`품절` · `프리오더`)는 이미지 위 뱃지 · pill 이 아니라 가격 줄 뒤 글자로.
- 본문(설명 · 제작 포인트 · 룩 설명)은 `fg`, 회색(`muted-fg`)은 보조 정보(정가 · 메뉴 설명 · 안내)에만.
- 그림자 · 그라데이션 · 둥근 카드 · 유리 효과 · 이모지 · 한 단어만 이탤릭/색 강조를 쓰지 않는다.

## 3. 메뉴 (navigation)

헤더 메뉴는 컴포넌트가 아니라 브랜드 설정 `brand.content[locale].navigation`(`NavItem[]`, 타입은 `@cf/ui`)이 갖는다. 사업자마다 메뉴가 다르기 때문이다.

브랜드 문구(SEO · 메뉴 · 푸터 링크 · 메인 섹션)는 언어마다 `brand.content.ko` · `brand.content.en` 으로 나눈다(`BrandContent`). `brand.locales` 에 둔 언어는 모두 채운다. `href` 는 언어 접두어 없는 앱 경로로 적는다 — 앱의 `Link` 가 붙인다.

| 필드 | 쓰는 곳 |
|------|---------|
| `label` · `description` · `href` | 전체 메뉴 패널 — 대제목 + 한 줄 설명 (2열, 모바일 1열 전체 화면) |
| `primary: true` | PC(`lg` 이상) 헤더의 로고 아래 1차 메뉴 |
| `shortcut: true` | 모바일 헤더의 바로가기 줄 — 첫 화면에서 핵심 목록으로 가는 길 |

- 순서는 배열 순서다. 메뉴를 바꿀 때 `apps/storefront/src/components/header/*` 를 고치지 않는다.
- 아직 없는 화면도 링크로 둘 수 있다. 앱의 `not-found.tsx` 가 "준비 중" 문구로 받는다(상태는 404).

### 3-1. 메인 화면 (home)

메인 섹션도 브랜드 설정 `brand.content[locale].home`(`HomeSection[]`, 타입은 `@cf/ui`)이 위에서부터 정한다. `apps/storefront/src/components/home/*` 는 데이터만 그린다.

| 유형 | 내용 | 데이터 |
|------|------|--------|
| `HERO` | 라벨 · 제목(줄 배열) · 문구 · CTA, PC 16:9 / 모바일 4:5 화보(따로), 선택 영상 | 설정 |
| `PRODUCT_PICK` | 대표 상품 n개 | 상품 목록 API(`categoryId` · `limit`) — 이름 · 가격을 설정에 적지 않는다 |
| `STORY` | 라벨 · 제목 · 본문 · 링크, `asymmetric`(이미지 2장 비대칭) / `centered` | 설정 |
| `LOOK` | 착장 화보 + 구성 상품(옵션 선택 · 함께 담기) | 상품 상세 API(`productIds`) |
| `DETAIL` | 클로즈업 칸 + 번호 붙은 제작 포인트 | 설정 |

- 유형 이름은 BE 메인 전시 API(#63)의 섹션 유형과 같다. API 가 생기면 `content.home` 대신 응답을 넘긴다. `ROMANTIC_EDIT` 같은 브랜드 이름은 유형이 아니라 `title` 값이다.
- 화보가 없으면 `image: null` 과 `placeholder`(필요한 사진 · 비율)를 둔다. 같은 비율의 빈 자리가 그려져 레이아웃이 밀리지 않는다.
- API 를 쓰는 섹션은 실패하거나 비면 그리지 않는다(메인을 깨뜨리지 않는다).
- 새 유형을 더하면 `HomeSections` 의 `switch` 가 컴파일 오류로 알려 준다.

### 3-2. Shop the Look

메인 `LOOK` 과 상품 상세 SHOP THE LOOK 은 같은 구성품 목록 `components/product/look/LookPicker` 를 쓴다.

- 상세는 룩 API(#62 초안) `GET /api/v1/products/{productId}/looks` 를 부른다. 없거나(404) 실패하거나 비면 영역을 그리지 않는다. 룩은 4개까지, 하나면 화보 | 구성품 두 칸, 여럿이면 PC 3열 · 모바일 가로 스와이프(72vw).
- 구성품의 옵션 · SKU 는 룩 응답에 없어 상품 상세 API 로 받는다(같은 상품은 한 번). 못 받은 상품은 빠지고, 구성품이 모두 빠진 룩은 그리지 않는다.
- 판매중 · 일반 상품만 함께 담기에 넣은 채로 시작한다. 품절 · 판매 종료는 막고 상태를 글자로, 맞춤 제작은 치수가 필요해 상품 상세로 보낸다.
- 담을 SKU 는 `lookCartLines` 가 정한다. 옵션이 덜 골라진 상품이 하나라도 있으면 담지 않는다(일부만 담기지 않게). 합계는 보이지 않는다 — 금액은 장바구니가 서버 값으로 보인다.
- 장바구니(#26) 전까지 "함께 담기" 버튼은 막아 두고 무엇이 남았는지만 알린다.

## 4. 고객사 정보

사업자등록번호·대표자 등 실정보는 테마에 넣지 않는다(공개 저장소). 서버 env(`BUSINESS_*`)로 받는다.

- 스토어프론트 푸터(`components/footer/SiteFooter`)가 `src/lib/env.ts` 의 `businessInfo` 로 표시한다(상호 · 대표 · 사업자등록번호 · 통신판매업 신고번호 · 주소 · 고객센터). 빈 값은 그 줄을 빼고, 사업자등록번호가 10자리면 공정위 사업자 정보 확인 링크를 단다.
- 정적으로 미리 그리는 페이지(not-found 등)에는 **빌드 시점** 값이 들어간다. 배포 환경은 빌드 · 실행 모두에 같은 값을 준다.
- 푸터 고객 안내 링크(약관 · 개인정보처리방침 · 고객센터)는 브랜드 설정 `brand.content[locale].footerLinks` 다. 개인정보처리방침은 `emphasis: true` 로 굵게 둔다.
- 테스트 · 예시에는 실정보가 아닌 자리 값(`000-00-00000`, `홍길동`)만 쓴다.

## 5. 새 브랜드 추가

1. `packages/theme-mokoyuri` 를 복사해 `packages/theme-<brand>` 를 만들고 이름·`theme.css` 값·`brand.ts`(여는 언어 `locales`, 언어별 `content`)를 바꾼다.
2. 앱 `package.json` 의 `"@cf/brand": "workspace:@cf/theme-<brand>@*"` 한 줄을 바꾼다.
3. `pnpm install && pnpm build`.

## 6. 현재 MOKOYURI 테마 — "Romantic Heritage" (#57)

배경은 아이보리, 핑크는 일부 구간만. 고급스러운 로맨틱 70% · 한국적 정체성 20% · 현대적 미니멀 10%.

| 토큰 | 값 | 이름 | 대비(배경 대비) |
|------|-----|------|-----------------|
| `bg` | `#f7f3ef` | Porcelain | — |
| `fg` · `primary` | `#302b2c` | Soft Black | 12.6:1 |
| `muted-fg` | `#6e5f5e` | Dusty Rose 를 어둡게 | 5.5:1 |
| `border` | `#b9a7a5` | Dusty Rose | 입력 · 선택 컨트롤 테두리 전용 (글자로 쓰면 2.1:1 로 AA 미달) |
| `accent` | `#e7d4d7` | Blush | 위 글자는 `accent-fg`(Soft Black, 9.8:1) |
| `muted` | `#efe8e3` | Porcelain 한 단계 짙게 | — |

- 모서리 `--cf-radius-md: 0` — 이미지 · 버튼을 각지게, 둥근 카드 UI 를 쓰지 않는다.
- 최소 글자 `--cf-text-xs: 0.8125rem`(13px) — #57 §4 "13px 미만 금지". 메뉴 · 상품명 · 가격 · 법정 정보의 Small 단계 하한이다.
- 글꼴: 본문 Pretendard(가변), 영문 제목 Cormorant Garamond(300 · 400 · 400 italic), 한글 감성 문구 Noto Serif KR(400). 모두 OFL-1.1.
  마루부리는 npm 배포가 없어 Noto Serif KR 로 갔다 — 바꾸려면 `--cf-font-serif` 와 import 만 바꾼다.
- 세일 · 강조용 버건디 포인트는 아직 넣지 않았다(고객사 확인 대상).
