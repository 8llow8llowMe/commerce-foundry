import type { BrandConfig } from '@cf/ui';
import { home } from './home';
import { homeEn } from './home-en';

export const brand: BrandConfig = {
  name: 'MOKOYURI',
  logoSrc: null,
  defaultLocale: 'ko',
  locales: ['ko', 'en'],
  content: {
    ko: {
      seo: {
        titleTemplate: '%s | MOKOYURI',
        // 최종 문구는 고객사 확인 (#57)
        description: '한복에서 출발한 로맨틱 디자이너 패션 브랜드 MOKOYURI',
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
        {
          label: 'JOURNAL',
          description: '스타일링 / 화보 / 소식',
          href: '/journal',
          primary: true,
        },
        { label: 'STOCKISTS', description: '입점처 / 해외 구매', href: '/stockists' },
        { label: 'CUSTOMER CARE', description: '배송 / 교환 / 문의', href: '/customer-care' },
      ],
      // 약관 · 방침 본문 페이지는 고객사 문서를 받아 만든다 — 그 전에는 not-found(준비 중)가 받는다
      footerLinks: [
        { label: '이용약관', href: '/terms' },
        { label: '개인정보처리방침', href: '/privacy', emphasis: true },
        { label: '배송 · 교환 안내', href: '/customer-care' },
        { label: '고객센터', href: '/customer-care' },
      ],
      // 촬영 전 — 빈 자리면 어두운 단색 면으로 둔다(화보 placeholder 는 촬영 안내라 국문 그대로)
      footerBackdrop: {
        image: null,
        placeholder:
          '메인 마지막 화보 · 전폭 가로 · 아래쪽이 어둡고 여백이 넉넉한 컷(법정 정보 13px 가 얹힌다)',
      },
      // 배송비 · 기간 · 교환 조건과 사이즈 치수는 고객사 확인 값만 넣는다 — 받기 전에는 숨긴다(#60)
      productInfo: { sizeGuide: null, shipping: null },
      home,
    },
    // 영문 문구는 초안이다 — 고객사 확인 대상 (#31)
    en: {
      seo: {
        titleTemplate: '%s | MOKOYURI',
        description: 'MOKOYURI — a romantic designer fashion label rooted in hanbok',
      },
      navigation: [
        {
          label: 'SHOP',
          description: 'All products / Categories',
          href: '/shop',
          primary: true,
          shortcut: true,
        },
        {
          label: 'COLLECTION',
          description: 'Seasonal collections / Lookbook',
          href: '/collection',
          primary: true,
          shortcut: true,
        },
        {
          label: 'OUR STORY',
          description: 'Philosophy / How we make',
          href: '/our-story',
          primary: true,
        },
        {
          label: 'JOURNAL',
          description: 'Styling / Editorials / News',
          href: '/journal',
          primary: true,
        },
        { label: 'STOCKISTS', description: 'Stockists / International orders', href: '/stockists' },
        {
          label: 'CUSTOMER CARE',
          description: 'Shipping / Returns / Contact',
          href: '/customer-care',
        },
      ],
      footerLinks: [
        { label: 'Terms of Use', href: '/terms' },
        { label: 'Privacy Policy', href: '/privacy', emphasis: true },
        { label: 'Shipping & Returns', href: '/customer-care' },
        { label: 'Customer Care', href: '/customer-care' },
      ],
      footerBackdrop: {
        image: null,
        placeholder:
          '메인 마지막 화보 · 전폭 가로 · 아래쪽이 어둡고 여백이 넉넉한 컷(법정 정보 13px 가 얹힌다)',
      },
      // 배송비 · 기간 · 교환 조건과 사이즈 치수는 고객사 확인 값만 넣는다 — 받기 전에는 숨긴다(#60)
      productInfo: { sizeGuide: null, shipping: null },
      home: homeEn,
    },
  },
};
