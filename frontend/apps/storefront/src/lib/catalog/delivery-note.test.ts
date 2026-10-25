import { describe, expect, it } from 'vitest';
import { deliveryNote } from './delivery-note';
import type { ShippingNotice } from './shipping-policy';

const shipping: ShippingNotice = {
  lines: [
    '배송비 3,000원',
    '50,000원 이상 구매 시 무료배송',
    '제주 · 도서산간 지역은 추가 배송비가 있습니다.',
  ],
  freeShipping: '50,000원 이상 구매 시 무료배송',
};

describe('deliveryNote', () => {
  it('기본 배송비 · 무료배송 기준만 담는다(도서산간 줄은 아코디언에)', () => {
    expect(deliveryNote(shipping)).toEqual({
      lines: ['배송비 3,000원', '50,000원 이상 구매 시 무료배송'],
    });
  });

  it('무료배송 기준이 없으면 기본 배송비 한 줄이다', () => {
    expect(deliveryNote({ lines: ['무료배송'], freeShipping: null })).toEqual({
      lines: ['무료배송'],
    });
  });

  it('정책이 없거나 줄이 비면 null — 안내 줄을 그리지 않는다', () => {
    expect(deliveryNote(null)).toBeNull();
    expect(deliveryNote({ lines: [], freeShipping: null })).toBeNull();
  });
});
