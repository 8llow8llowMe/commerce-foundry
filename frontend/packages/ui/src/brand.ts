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

/** 브랜드 테마 패키지(theme-<brand>)가 채우는 설정. 고객사 실정보(사업자번호 등)는 넣지 않는다. */
export type BrandConfig = {
  name: string;
  logoSrc: string | null;
  defaultLocale: 'ko-KR' | 'en-US';
  seo: {
    /** 예: '%s | 브랜드명' */
    titleTemplate: string;
    description: string;
  };
  /** 전체 메뉴 순서대로 */
  navigation: NavItem[];
};
