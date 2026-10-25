import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
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
  it('일반 상품은 기본 배송비 · 무료배송 기준만 담는다(도서산간 줄은 아코디언에)', () => {
    expect(deliveryNote(null, shipping, ko.product.delivery)).toEqual({
      lines: ['배송비 3,000원', '50,000원 이상 구매 시 무료배송'],
    });
  });

  it('무료배송 기준이 없으면 기본 배송비 한 줄이다', () => {
    expect(
      deliveryNote(null, { lines: ['무료배송'], freeShipping: null }, ko.product.delivery),
    ).toEqual({ lines: ['무료배송'] });
  });

  it('맞춤 제작 기간이 있으면 맨 앞에 둔다 — 정책이 없어도 보인다', () => {
    expect(deliveryNote({ leadTimeDays: 14 }, shipping, ko.product.delivery)?.lines).toEqual([
      '주문 후 약 14일 제작 뒤 발송',
      '배송비 3,000원',
      '50,000원 이상 구매 시 무료배송',
    ]);
    expect(deliveryNote({ leadTimeDays: 7 }, null, en.product.delivery)).toEqual({
      lines: ['Made to order in about 7 days, then shipped'],
    });
  });

  it('제작 기간도 정책도 없으면 null — 안내 줄을 그리지 않는다', () => {
    expect(deliveryNote(null, null, ko.product.delivery)).toBeNull();
    expect(deliveryNote({ leadTimeDays: null }, null, ko.product.delivery)).toBeNull();
  });
});
