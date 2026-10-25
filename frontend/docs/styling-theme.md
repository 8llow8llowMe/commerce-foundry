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
| `HERO` | 제목(줄 배열) · 문구 · 주 버튼 `cta` + 보조 `secondaryCta`, PC · 모바일 세로 4:5 화보(따로), 선택 영상 | 설정 |
| `PRODUCT_PICK` | 대표 상품 n개 | 상품 목록 API(`categoryId` · `limit`) — 이름 · 가격을 설정에 적지 않는다 |
| `STORY` | 제목 · 본문 · 링크, `asymmetric`(이미지 2장 비대칭) / `centered` / `curtain`(전폭 사진 2~3장 스크롤 고정 · 캡션) / `panel`(어두운 면 · 단락 · 모티프 사진) | 설정 |
| `LOOK` | 착장 화보 + 구성 상품(옵션 선택 · 함께 담기) | 상품 상세 API(`productIds`) |
| `DETAIL` | Display 제목 + 같은 전신 사진(`frame`)을 스크롤 · 지점 클릭으로 확대(포인트별 `focus`). 스크립트 없으면 클로즈업 + 포인트 세로 | 설정 |

- 유형 이름은 BE 메인 전시 API(#63)의 섹션 유형과 같다. API 가 생기면 `content.home` 대신 응답을 넘긴다. `ROMANTIC_EDIT` 같은 브랜드 이름은 유형이 아니라 `title` 값이다.
- 화보가 없으면 `image: null` 과 `placeholder`(필요한 사진 · 비율)를 둔다. 같은 비율의 빈 자리가 그려져 레이아웃이 밀리지 않는다.
- API 를 쓰는 섹션은 실패하거나 비면 그리지 않는다(메인을 깨뜨리지 않는다).
- 새 유형을 더하면 `HomeSections` 의 `switch` 가 컴파일 오류로 알려 준다.
- 섹션 간격은 모바일 96 · 태블릿 120 · PC 160px(`HomeSections`), 섹션 제목은 Title 단계(28 / 48px). 레이아웃 명세 정본은 #59 댓글이다.
- 첫 화면을 채우거나 스크롤 고정(sticky)하는 요소는 헤더 높이 `--site-header-h`(`app/globals.css`)를 기준으로 한다 — `h-[calc(100svh-var(--site-header-h))]`, `top-[var(--site-header-h)]`. 헤더 줄 높이를 바꾸면 이 값도 바꾼다.
- 브랜드 모티프 모양은 토큰 `--cf-motif-radius`(border-radius 값, 중립은 0)다 — MOKOYURI 는 저고리 배래 곡선. 페이지당 1~2장(메인 Our Story 인물 사진 등)에만 쓰고 상품 이미지에는 쓰지 않는다.
- 섹션 첫 등장 모션(#57 §6): `HomeSections` 가 히어로를 뺀 섹션을 `Reveal` 로 감싸고, 섹션 안 요소에 `data-reveal="title|text|media"` 를 단다(`SectionHeading` · `MediaFrame` 은 기본으로 단다). 지연은 종류로 정한다(제목 0 → 글 75 → 사진 150ms, 24px). 하이드레이션 뒤 화면 아래 섹션만 숨김으로 바꾸므로 서버 렌더 · 스크립트 실패 · 움직임 줄이기면 그대로 보인다. 고정 위치 버튼을 품은 요소(`LookPicker`)에는 달지 않는다 — 움직이는 동안 transform 이 fixed 의 기준을 바꾼다.
- 스크롤에 따라 바뀌는 화면(Craft 확대)은 서버 렌더 · 하이드레이션 전에 스크립트 없는 기본 화면을 그린다(`useSyncExternalStore` 로 하이드레이션 여부를 읽는다 — effect 안 setState 금지). transform 만 움직이고 `motion-reduce` 면 전환을 끈다.
- 메인 `LOOK` 의 `LookPicker` 는 `floatingAction` 으로 모바일 하단 고정 담기 버튼을 쓴다(목록이 보일 때만). 상품 상세는 구매 패널 하단 바가 있어 쓰지 않는다.
- 가로 스와이프 목록(`overflow-x-auto`)에는 `relative` 를 준다 — 안의 `sr-only`(absolute)가 스크롤 상자 밖 기준으로 잡혀 페이지 가로 스크롤을 만든다.
- 상품 상세 이미지(`ProductGallery`, #60)는 4:5 · 각진 모서리. PC 는 7열 폭에 한 장씩 세로로 이어 붙이고(오른쪽 5열 구매 정보가 sticky), 모바일은 전폭 가로 스와이프 + 아래 `1 / n` 글자(점 금지). 목록 카드는 3:4 그대로다.
  착용 영상(`images[].mediaType === 'VIDEO'`, BE #61)은 같은 4:5 칸에 노출 순서대로 섞인다(`ProductVideo`). 포스터(`posterUrl`) + 재생 표시를 먼저 보이고 영상 파일은 받지 않는다. 누르면 그 자리에서 `<video controls muted playsInline>` 로 바꿔 재생한다 — 자동재생 없음, 소리는 꺼진 채 시작, 움직임 줄이기 설정이면 반복하지 않고, 가로 스와이프로 화면 밖에 나가면 멈춘다. 크게 보기는 이미지만 넘겨 보고 `1 / n` 위치 글자는 영상까지 센다. 영상 URL 은 BE 가 우리 스토리지 URL 만 내려 화면에서 거르지 않는다. JSON-LD `image` 에는 영상을 넣지 않는다.
- 상품 상세 모바일 첫 화면에 가격 · 배송 안내 · 구매 버튼이 보여야 한다(하단 고정 바 포함) — 사진을 헤더 바로 아래에 붙이고 경로(breadcrumb)는 PC 에서만, 요약은 모바일에서 구매 패널 아래로 내린다. 하단 바는 패널의 수량 · 담기 버튼 묶음이 바 위로 올라와 보일 때만 숨는다(가격 줄을 기준으로 하면 화면 높이에 따라 첫 화면부터 숨는다).
- 배송 안내(`lib/catalog/delivery-note`)는 가격 바로 아래 · 하단 바에 보인다 — 프리오더 발송 예정 + 배송비 정책(기본 배송비 · 무료배송 기준). 맞춤 제작 기간은 맞춤 제작 안내 블록에만 둔다(중복 금지).
  - **프리오더**(상세 `preorder`, BE #61 · api-design-guide §2-7)는 `프리오더 · 11월 20일부터 순차 발송`(예정일이 null 이거나 읽을 수 없으면 `프리오더 · 발송일 추후 안내`) 한 줄을 `bg-accent` 블록으로 가격 바로 아래 · 바텀시트 상단에 보이고, 하단 바 한 줄은 배송비 대신 이 문구다. 배송비 줄은 일반 상품과 같이 그 아래에 둔다. 일반 상품은 블록이 없다.
  - 예정일은 `yyyy-MM-dd`(스토어 현지 날짜, 시간대 없음)라 `new Date(문자열)` 로 읽지 않는다(`parseDateOnly` — UTC 날짜로 고정). 표시는 스토어 언어 `Intl.DateTimeFormat`(국문 `11월 20일`, 영문 `November 20`), 올해가 아니면 연도를 붙인다. 상세는 서버에서 문구를 만들어 넘긴다(`preorderNote`).
  - 프리오더의 판매 가능 · 품절 판정은 일반 상품과 같다 — 화면은 안내만 더하고 구매 흐름(`purchaseTarget`)은 바꾸지 않는다.
- 하단 바의 구매하기는 옵션 · 수량 · 담기를 담은 바텀시트(`<dialog>`)를 연다 — 패널과 같은 선택 상태를 쓴다. 맞춤 제작 · 판매중이 아닌 상품은 패널로 스크롤한다(`purchaseTarget`). 시트의 여백은 dialog 가 아니라 안쪽 wrapper 에 둔다(dialog 를 직접 누른 것만 배경 클릭). 열린 동안 뒤 페이지 스크롤을 막고, `lg` 이상으로 넓어지면 닫는다(보이지 않는 modal 이 페이지를 inert 로 만든다).
- 상품 목록 카드(`ProductCard`, #60)는 모바일 2열 · 좌우 여백과 가로 간격 8px · 이름 한 줄 말줄임 · 13px, PC 4열 · 24 / 56px · 14px. 두 번째 이미지(`hoverImage`)가 있으면 hover · 포커스 때 겹쳐 바꾸고, 한 장이면 살짝 확대만 한다. `hoverImage` 는 목록 `GET /api/v1/products` · 메인 전시 상품 카드가 함께 주는 BE 확정 필드다(BE #300, 모양은 `thumbnail` 과 같다) — 정지 이미지 중 두 번째이고 영상은 세지 않으며, 1장 이하면 `null` 이다. 메인 상품 픽 카드도 같은 `ProductCard` 라 똑같이 바뀐다. 목록 `preorder: true` 면 가격 줄 뒤에 `· 프리오더` 를 글자로 붙인다(pill 금지). 판매중이 아니면 판매 상태만 보인다. 목록에는 예정일이 오지 않는다.
- 상품 상세 공통 안내(`ProductInfoAccordion`, #60)는 구매 패널 아래 `<details>` 아코디언이다(선 없이 여백, 스크립트 없이 동작). 사이즈 가이드 표 · 배송 · 교환 문구는 브랜드 설정 `content[locale].productInfo` 이고, null 인 항목은 그리지 않는다 — 기간 · 교환 조건 · 치수는 고객사 확인 값만 넣는다. 배송비 줄은 브랜드 설정이 아니라 delivery 정책 API 값이다(`architecture.md` §8, #171) — 정책이 있으면 브랜드 문단이 없어도 배송 · 교환 항목을 그리고 맨 위에 둔다. 상품마다 다른 소재 · 관리 · 사이즈 · 핏은 상품 데이터가 생기면 같은 아코디언에 더한다.
- 상품 목록 모바일(`ShopFilters`, #60)은 최상위 카테고리 줄만 화면에 두고, 정렬 · 세부 카테고리는 하단 고정 버튼 `정렬 · 필터` 로 여는 바텀시트에 둔다. 시트는 Popover API(`popover` · `popoverTarget`)라 스크립트 없이 열리고 바깥 · ESC 로 닫힌다. 시트에 `key={shopHref(query)}` 를 줘서 링크로 주소가 바뀌면 닫힌 채로 다시 그린다.

### 3-2. Shop the Look

메인 `LOOK` 과 상품 상세 SHOP THE LOOK 은 같은 구성품 목록 `components/product/look/LookPicker` 를 쓴다.

- 상세는 룩 API(#62 초안) `GET /api/v1/products/{productId}/looks` 를 부른다. 없거나(404) 실패하거나 비면 영역을 그리지 않는다. 룩은 4개까지, 하나면 화보 | 구성품 두 칸, 여럿이면 PC 3열 · 모바일 가로 스와이프(72vw).
- 구성품의 옵션 · SKU 는 룩 응답에 없어 상품 상세 API 로 받는다(같은 상품은 한 번). 못 받은 상품은 빠지고, 구성품이 모두 빠진 룩은 그리지 않는다.
- 판매중 · 일반 상품만 함께 담기에 넣은 채로 시작한다. 품절 · 판매 종료는 막고 상태를 글자로, 맞춤 제작은 치수가 필요해 상품 상세로 보낸다.
- 담을 SKU 는 `lookCartLines` 가 정한다. 옵션이 덜 골라진 상품이 하나라도 있으면 담지 않는다(일부만 담기지 않게). 합계는 보이지 않는다 — 금액은 장바구니가 서버 값으로 보인다.
- 장바구니(#26) 전까지 "함께 담기" 버튼은 막아 두고 무엇이 남았는지만 알린다.

## 4. 고객사 정보

사업자등록번호·대표자 등 실정보는 테마에 넣지 않는다(공개 저장소). 서버 env(`BUSINESS_*`)로 받는다.

- 스토어프론트 푸터(`components/footer/SiteFooter`)가 `src/lib/env.ts` 의 `businessInfo` 로 표시한다 — 법정 정보(상호 · 대표 · 사업자등록번호 · 통신판매업 신고번호 · 주소)는 아래 13px, 고객센터 연락처는 위 줄 오른쪽 전화 링크. 빈 값은 그 줄을 빼고, 사업자등록번호가 10자리면 공정위 사업자 정보 확인 링크를 단다.
- 푸터 구성(#59): 위 줄 링크 · 연락처 → 화면 폭 가득 워드마크(`Wordmark`, 브랜드 이름을 SVG `textLength` 로 자간만 벌려 양 끝을 맞춘다) → 법정 정보. 모바일은 링크 1열부터 같은 순서다.
- 메인에서만 브랜드 설정 `brand.content[locale].footerBackdrop` 화보를 밝기 0.62 로 깔고 글을 Porcelain(`text-bg`)으로 바꾼다. 화보가 null 이면 같은 어두운 단색 면이다. 메인 판별은 `useSelectedLayoutSegment() === null`(`FooterSurface`) — proxy rewrite 뒤 `usePathname` 은 주소와 다를 수 있다. 그라데이션 금지라 스크림을 두지 않으니 화보는 아래쪽이 어두운 컷으로 고른다.
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
