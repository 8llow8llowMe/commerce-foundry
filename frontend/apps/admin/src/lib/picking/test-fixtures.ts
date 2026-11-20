import type { PickingBatch } from './types';

/** 테스트용 출고 묶음 상세 — 주문 2건(하나는 검수 완료) · 빠진 주문 1건 */
export function pickingBatch(overrides: Partial<PickingBatch> = {}): PickingBatch {
  return {
    batchId: '9',
    version: 1,
    inspectionComplete: false,
    totalQuantity: 3,
    pickingQuantities: [
      { skuId: '10', quantity: 2 },
      { skuId: '11', quantity: 1 },
    ],
    orders: [
      {
        orderId: '1',
        status: 'PREPARING',
        countryCode: 'KR',
        checked: false,
        quantities: [{ skuId: '10', quantity: 2 }],
      },
      {
        orderId: '2',
        status: 'PREPARING',
        countryCode: 'KR',
        checked: true,
        quantities: [{ skuId: '11', quantity: 1 }],
      },
    ],
    exclusions: [
      {
        orderId: '3',
        reason: { code: 'NOT_PREPARING', name: '상품 준비중 아님', description: '' },
      },
    ],
    checks: [
      {
        orderId: '2',
        successful: true,
        reason: { code: 'NONE', name: '성공', description: '' },
        quantities: [{ skuId: '11', quantity: 1 }],
      },
    ],
    ...overrides,
  };
}
