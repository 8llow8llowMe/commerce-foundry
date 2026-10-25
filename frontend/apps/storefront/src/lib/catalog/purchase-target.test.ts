import { describe, expect, it } from 'vitest';
import { purchaseTarget } from './purchase-target';
import type { SaleStatusCode } from './types';

const status = (code: SaleStatusCode) => ({ code, name: code, description: '' });
const customOrder = { notice: null, leadTimeDays: 14, fields: [] };

describe('purchaseTarget', () => {
  it('판매중인 일반 상품은 바텀시트를 연다', () => {
    expect(purchaseTarget({ customOrder: null, saleStatus: status('ON_SALE') })).toBe('sheet');
  });

  it('맞춤 제작 상품은 판매중이어도 구매 패널로 간다', () => {
    expect(purchaseTarget({ customOrder, saleStatus: status('ON_SALE') })).toBe('panel');
  });

  it('품절 · 판매 종료 상품은 구매 패널로 간다', () => {
    expect(purchaseTarget({ customOrder: null, saleStatus: status('SOLD_OUT') })).toBe('panel');
    expect(purchaseTarget({ customOrder: null, saleStatus: status('STOP_SELLING') })).toBe('panel');
  });
});
