import type { HomeMediaSlot, HomeSection } from './home';

/** 전체 메뉴 항목. 사업자마다 메뉴가 다르므로 컴포넌트가 아니라 브랜드 설정이 갖는다. */
export type NavItem = {
  /** 대제목 (예: 'SHOP') */
  label: string;
  /** 대제목 아래 한 줄 설명 (예: '전체 상품 / 카테고리') */
  description: string;
  href: string;
  /** PC 헤더의 로고 아래 1차 메뉴에 노출한다 */
  primary?: boolean;
  /** 모바일 헤더에 바로가기로 노출한다. 첫 화면에서 핵심 목록으로 가는 길이다 */
  shortcut?: boolean;
};

/** 푸터 고객 안내 링크 (약관 · 개인정보처리방침 · 고객센터 등) */
export type FooterLink = {
  label: string;
  href: string;
  /** 굵게 — 개인정보처리방침은 다른 약관과 구분해 눈에 띄게 둔다 */
  emphasis?: boolean;
};

/** 사이즈 가이드 표 — 상점 공통 치수. 상품마다 다른 치수(사이즈 · 핏)는 상품 데이터로 받는다(BE) */
export type SizeGuide = {
  /** 머리줄. 첫 칸은 사이즈 열 이름 (예: ['사이즈', '가슴둘레', '총장']) */
  columns: string[];
  /** 줄마다 columns 와 같은 수의 칸. 첫 칸이 사이즈 이름 */
  rows: string[][];
  /** 표 아래 안내 한 줄 (예: '단위 cm, 재는 방법에 따라 1~2cm 차이'). 없으면 null */
  note: string | null;
};

/** 상품 상세 아래 아코디언의 상점 공통 안내(#60). 값이 null 인 항목은 그리지 않는다 */
export type ProductInfo = {
  sizeGuide: SizeGuide | null;
  /** 배송 · 교환 안내, 단락마다 하나 */
  shipping: string[] | null;
};

/** 스토어 언어. URL 접두어(`/en`) · `<html lang>` · 사전 키로 쓴다 */
export type Locale = 'ko' | 'en';

/** 언어마다 다른 브랜드 문구. 링크 href 는 언어 접두어 없는 경로로 적는다 — 앱이 붙인다 */
export type BrandContent = {
  seo: {
    /** 예: '%s | 브랜드명' */
    titleTemplate: string;
    description: string;
  };
  /** 전체 메뉴 순서대로 */
  navigation: NavItem[];
  /** 푸터 고객 안내 링크, 순서대로. 사업자 실정보는 여기 두지 않는다(서버 env BUSINESS_*) */
  footerLinks: FooterLink[];
  /** 메인 푸터의 전폭 배경 화보(#59 푸터) — 메인에서만 깔린다. 없으면 null(목록 · 상세와 같은 푸터) */
  footerBackdrop: HomeMediaSlot | null;
  /** 상품 상세 공통 안내(사이즈 가이드 · 배송 · 교환). 고객사 값을 받기 전에는 null 로 숨긴다 */
  productInfo: ProductInfo;
  /** 메인 화면 섹션, 위에서부터. 메인 전시 API(#63)가 생기면 그 응답으로 바꾼다 */
  home: HomeSection[];
};

/** 브랜드 테마 패키지(theme-<brand>)가 채우는 설정. 고객사 실정보(사업자번호 등)는 넣지 않는다. */
export type BrandConfig = {
  name: string;
  logoSrc: string | null;
  /** 접두어 없는 URL 의 언어. supported 의 첫 값이어야 한다 */
  defaultLocale: Locale;
  /** 이 스토어가 여는 언어. 하나뿐이면 언어 전환 · 접두어가 없다 */
  locales: readonly Locale[];
  /** 언어별 문구. 여는 언어는 모두 채운다 */
  content: Record<Locale, BrandContent>;
};
