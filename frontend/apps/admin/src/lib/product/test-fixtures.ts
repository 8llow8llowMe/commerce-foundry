import type { AdminProductDetail } from './detail';

/** 테스트용 어드민 상품 상세. 옵션 2그룹(사이즈의 L 은 은퇴) · SKU 3개(하나는 판매 종료) · 이미지 · 영상 */
export function adminProduct(overrides: Partial<AdminProductDetail> = {}): AdminProductDetail {
  return {
    productId: '4611686018427387904',
    categoryId: '102',
    categoryIds: ['101', '102'],
    name: '테스트 저고리',
    summary: '가볍게 입는 저고리',
    descriptionHtml: '<p>소재 <strong>면</strong></p>',
    saleStatus: { code: 'ON_SALE', name: '판매중', description: '판매 중인 상품' },
    visible: false,
    salePrice: { amount: 129000, currency: 'KRW' },
    listPrice: { amount: 159000, currency: 'KRW' },
    maxQuantityPerOrder: null,
    customOrder: false,
    customOrderNotice: null,
    customOrderLeadTimeDays: null,
    preorder: false,
    expectedShipDate: null,
    version: 3,
    createdByMemberId: '9001',
    optionGroups: [
      {
        optionGroupId: '201',
        name: '색상',
        displayOrder: 0,
        retiredAt: null,
        values: [{ optionValueId: '301', name: '쪽빛', displayOrder: 0, retiredAt: null }],
      },
      {
        optionGroupId: '202',
        name: '사이즈',
        displayOrder: 1,
        retiredAt: null,
        values: [
          { optionValueId: '311', name: 'M', displayOrder: 0, retiredAt: null },
          {
            optionValueId: '312',
            name: 'L',
            displayOrder: 1,
            retiredAt: '2026-10-01T03:00:00Z',
          },
        ],
      },
    ],
    skus: [
      {
        skuId: '401',
        // 서버 순서와 상관없이 그룹 진열 순서로 이름을 붙인다
        optionValueIds: ['311', '301'],
        additionalPrice: { amount: 0, currency: 'KRW' },
        saleStatus: { code: 'ON_SALE', name: '판매중', description: '판매 중' },
        weightGrams: 450,
        hsCode: '6211.42',
      },
      {
        skuId: '402',
        optionValueIds: ['301', '312'],
        additionalPrice: { amount: 5000, currency: 'KRW' },
        saleStatus: { code: 'STOP_SELLING', name: '판매 종료', description: '판매 종료' },
        weightGrams: null,
        hsCode: null,
      },
    ],
    images: [
      {
        imageId: '501',
        imageType: { code: 'THUMBNAIL', name: '대표', description: '목록 대표 이미지' },
        url: 'https://img.test/thumb.jpg',
        width: 800,
        height: 800,
        altText: '정면',
        displayOrder: 0,
        posterUrl: null,
      },
      {
        imageId: '502',
        imageType: { code: 'VIDEO', name: '영상', description: '상세 갤러리 영상' },
        url: 'https://img.test/clip.mp4',
        width: null,
        height: null,
        altText: null,
        displayOrder: 1,
        posterUrl: 'https://img.test/poster.jpg',
      },
    ],
    customOrderFields: [],
    ...overrides,
  };
}
