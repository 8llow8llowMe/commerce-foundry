// 앱 UI 문구(국문). 브랜드 문구(메뉴 · 메인 · 푸터 링크)는 브랜드 설정 content 에 있다.
// 키를 더하면 en.ts 도 채워야 타입 검사를 통과한다.
export const ko = {
  languageName: '한국어',
  header: {
    openMenu: '전체 메뉴 열기',
    closeMenu: '전체 메뉴 닫기',
    menu: '전체 메뉴',
    search: '검색',
    cart: '장바구니',
    shortcuts: '바로가기',
    primaryNav: '주 메뉴',
    switchLanguage: '언어 선택',
  },
  footer: {
    customerLinks: '고객 안내',
    business: '사업자 정보',
    businessName: '상호',
    representative: '대표',
    registrationNumber: '사업자등록번호',
    mailOrderNumber: '통신판매업 신고번호',
    address: '주소',
    contact: '고객센터',
    verify: '사업자 정보 확인',
  },
  notFound: {
    title: '준비 중이거나 없는 페이지입니다.',
    body: '주소를 확인하시거나 메인에서 다시 찾아 주세요.',
    home: '메인으로',
  },
};

export type Dictionary = typeof ko;
