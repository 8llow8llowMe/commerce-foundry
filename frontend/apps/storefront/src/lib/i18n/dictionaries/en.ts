import type { Dictionary } from './ko';

// 앱 UI 문구(영문). ko.ts 와 같은 키를 모두 채운다.
export const en: Dictionary = {
  languageName: 'English',
  header: {
    openMenu: 'Open menu',
    closeMenu: 'Close menu',
    menu: 'Menu',
    search: 'Search',
    cart: 'Bag',
    shortcuts: 'Shortcuts',
    primaryNav: 'Main',
    switchLanguage: 'Language',
  },
  footer: {
    customerLinks: 'Customer care',
    business: 'Company information',
    businessName: 'Company',
    representative: 'CEO',
    registrationNumber: 'Business registration no.',
    mailOrderNumber: 'Mail-order business report no.',
    address: 'Address',
    contact: 'Customer service',
    verify: 'Verify business information',
  },
  notFound: {
    title: 'This page is coming soon or does not exist.',
    body: 'Please check the address, or start again from the home page.',
    home: 'Back to home',
  },
};
