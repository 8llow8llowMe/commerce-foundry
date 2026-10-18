import type { BrandConfig } from '@cf/ui';

export const brand: BrandConfig = {
  name: 'MOKOYURI',
  logoSrc: null,
  defaultLocale: 'ko-KR',
  seo: {
    titleTemplate: '%s | MOKOYURI',
    description: '생활한복 MOKOYURI 온라인 스토어',
  },
  // 아직 없는 화면은 링크만 둔다(앱의 not-found 가 '준비 중' 으로 받는다).
  navigation: [
    {
      label: 'SHOP',
      description: '전체 상품 / 카테고리',
      href: '/shop',
      primary: true,
      shortcut: true,
    },
    {
      label: 'COLLECTION',
      description: '시즌별 컬렉션 / 룩북',
      href: '/collection',
      primary: true,
      shortcut: true,
    },
    {
      label: 'OUR STORY',
      description: '브랜드 철학 / 제작 이야기',
      href: '/our-story',
      primary: true,
    },
    { label: 'JOURNAL', description: '스타일링 / 화보 / 소식', href: '/journal', primary: true },
    { label: 'STOCKISTS', description: '입점처 / 해외 구매', href: '/stockists' },
    { label: 'CUSTOMER CARE', description: '배송 / 교환 / 문의', href: '/customer-care' },
  ],
};
