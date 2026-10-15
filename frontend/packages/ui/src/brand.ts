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
};
