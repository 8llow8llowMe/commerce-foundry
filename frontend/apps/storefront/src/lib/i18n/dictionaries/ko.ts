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
  shop: {
    categories: '카테고리',
    all: '전체',
    /** {name}: 최상위 카테고리 이름 */
    allOf: '{name} 전체',
    sort: '정렬',
    sortTypes: { NEWEST: '신상품순', PRICE_ASC: '낮은 가격순', PRICE_DESC: '높은 가격순' },
    /** 모바일 하단 고정 버튼 · 바텀시트 제목 */
    sortAndFilter: '정렬 · 필터',
    closeSheet: '닫기',
    loadFailed: '상품을 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
    reload: '다시 불러오기',
    loadMoreFailed: '상품을 더 불러오지 못했습니다.',
    empty: '아직 등록된 상품이 없습니다.',
    loading: '상품을 불러오는 중입니다.',
    retry: '다시 시도',
    more: '더 보기',
  },
  price: {
    /** 스크린리더용 — 숫자 앞뒤에 붙는다 */
    discountRate: '할인율',
    from: '부터',
    listPrice: '정가',
  },
  product: {
    imagePending: '이미지 준비 중',
    loadFailed: '상품 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.',
    reload: '다시 불러오기',
    breadcrumb: '카테고리 경로',
    gallery: {
      images: '{name} 이미지 {count}장',
      zoom: '{alt} 크게 보기',
      zoomTitle: '{name} 이미지',
      closeZoom: '크게 보기 닫기',
      prev: '이전 이미지',
      next: '다음 이미지',
    },
    purchase: {
      selectOptions: '옵션을 선택해 주세요.',
      optionStatus: '선택한 옵션은 {status} 입니다.',
      cartPending: '장바구니 담기는 준비 중입니다.',
      surcharge: '옵션 추가금 +{amount}',
      quantity: '수량',
      decrease: '수량 줄이기',
      increase: '수량 늘리기',
      maxPerOrder: '한 번에 최대 {count}개까지 주문할 수 있습니다.',
      addToCart: '장바구니 담기',
      buy: '구매하기',
    },
    option: { soldOut: '품절', unavailable: '선택 불가' },
    customOrder: {
      title: '맞춤 제작',
      leadTime: '제작 기간 약 {days}일',
      required: '필수',
      optional: '선택',
    },
  },
  home: {
    /** 05 Craft 확대에서 전신으로 돌아가기 */
    craftOverview: '전신 보기',
  },
  look: {
    selectOptions: '{names} 옵션을 선택해 주세요.',
    pickItems: '함께 담을 상품을 골라 주세요.',
    cartPending: '장바구니 담기는 준비 중입니다.',
    current: '지금 보는 상품',
    customOrder: '맞춤 제작 상품은 치수를 받아야 해요.',
    orderFromProduct: '상품에서 주문하기',
    viewProduct: '상품 보기',
    addAll: '함께 담기',
  },
  notFound: {
    title: '준비 중이거나 없는 페이지입니다.',
    body: '주소를 확인하시거나 메인에서 다시 찾아 주세요.',
    home: '메인으로',
  },
};

export type Dictionary = typeof ko;
