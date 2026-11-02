import type { BulkStatusChangeResponse, OrderDetail, OrderListItem } from './types';

const status = (code: string, name: string) => ({ code, name, description: `${name} 설명` });

/** 테스트용 주문 목록 한 줄 — 결제 완료 */
export function orderItem(overrides: Partial<OrderListItem> = {}): OrderListItem {
  return {
    orderId: '4611686018427387904',
    memberId: '4611686018427387000',
    status: status('PAID', '결제 완료'),
    orderName: '모시 저고리 외 1건',
    totalAmount: 93000,
    currency: 'KRW',
    recipientName: '홍*동',
    createdAt: '2026-10-18T09:00:00Z',
    paymentDueAt: '2026-10-18T09:30:00Z',
    ...overrides,
  };
}

/** 테스트용 주문 상세 — 결제 완료, 맞춤 제작 항목 하나, 다음 상태 제작 중 · 상품 준비중 */
export function orderDetail(overrides: Partial<OrderDetail> = {}): OrderDetail {
  return {
    orderId: '4611686018427387904',
    memberId: '4611686018427387000',
    status: status('PAID', '결제 완료'),
    nextStatuses: [status('MAKING', '제작 중'), status('PREPARING', '상품 준비중')],
    currency: 'KRW',
    items: [
      {
        skuId: '9004',
        productId: '501',
        productName: '모시 저고리',
        optionName: 'M · 쪽빛',
        unitPrice: { amount: 45000, currency: 'KRW' },
        quantity: 2,
        lineAmount: { amount: 90000, currency: 'KRW' },
        customOrder: true,
        customOptions: [{ fieldKey: 'chest', value: '96.5' }],
      },
    ],
    amounts: {
      itemsAmount: { amount: 90000, currency: 'KRW' },
      shippingFee: { amount: 3000, currency: 'KRW' },
      totalAmount: { amount: 93000, currency: 'KRW' },
    },
    shippingAddress: {
      recipientName: '홍*동',
      recipientPhone: '010****5678',
      countryCode: 'KR',
      postalCode: '63100',
      addressLine1: '제주특별자치도 제주시 첨단로 242',
      addressLine2: '****',
      city: null,
      stateProvince: null,
      deliveryMemo: '****',
    },
    histories: [
      {
        fromStatus: null,
        toStatus: status('PENDING_PAYMENT', '결제 대기'),
        changedBy: status('CUSTOMER', '고객'),
        changedById: '4611686018427387000',
        reason: null,
        changedAt: '2026-10-18T09:00:00Z',
      },
      {
        fromStatus: status('PENDING_PAYMENT', '결제 대기'),
        toStatus: status('PAID', '결제 완료'),
        changedBy: status('INTEGRATION', '연동 처리'),
        changedById: null,
        reason: null,
        changedAt: '2026-10-18T09:05:00Z',
      },
    ],
    paymentDueAt: '2026-10-18T09:30:00Z',
    createdAt: '2026-10-18T09:00:00Z',
    ...overrides,
  };
}

/** 세 건 중 하나 실패 */
export function bulkResponse(
  overrides: Partial<BulkStatusChangeResponse> = {},
): BulkStatusChangeResponse {
  return {
    changedCount: 1,
    unchangedCount: 1,
    failedCount: 1,
    results: [
      { orderId: '11', result: 'CHANGED', errorCode: null, message: null },
      { orderId: '12', result: 'UNCHANGED', errorCode: null, message: null },
      {
        orderId: '13',
        result: 'FAILED',
        errorCode: 'ORDER_020',
        message: '현재 주문 상태(취소 완료)에서는 할 수 없는 요청입니다.',
      },
    ],
    ...overrides,
  };
}
