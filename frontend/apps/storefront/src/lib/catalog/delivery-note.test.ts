import { describe, expect, it } from 'vitest';
import { deliveryHeadline, deliveryNote } from './delivery-note';
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
      preorder: null,
      lines: ['배송비 3,000원', '50,000원 이상 구매 시 무료배송'],
    });
  });

  it('무료배송 기준이 없으면 기본 배송비 한 줄이다', () => {
    expect(deliveryNote({ lines: ['무료배송'], freeShipping: null })).toEqual({
      preorder: null,
      lines: ['무료배송'],
    });
  });

  it('정책이 없거나 줄이 비면 null — 안내 줄을 그리지 않는다', () => {
    expect(deliveryNote(null)).toBeNull();
    expect(deliveryNote({ lines: [], freeShipping: null })).toBeNull();
  });

  it('프리오더 안내는 배송비 줄과 따로 담고, 배송비 줄은 그대로 둔다', () => {
    expect(deliveryNote(shipping, '프리오더 · 11월 20일부터 순차 발송')).toEqual({
      preorder: '프리오더 · 11월 20일부터 순차 발송',
      lines: ['배송비 3,000원', '50,000원 이상 구매 시 무료배송'],
    });
  });

  it('배송비 정책이 없어도 프리오더면 안내를 만든다', () => {
    expect(deliveryNote(null, '프리오더 · 발송일 추후 안내')).toEqual({
      preorder: '프리오더 · 발송일 추후 안내',
      lines: [],
    });
  });
});

describe('deliveryHeadline', () => {
  it('프리오더면 발송 예정, 아니면 기본 배송비 한 줄이다', () => {
    expect(deliveryHeadline(deliveryNote(shipping)!)).toBe('배송비 3,000원');
    expect(deliveryHeadline(deliveryNote(shipping, '프리오더 · 발송일 추후 안내')!)).toBe(
      '프리오더 · 발송일 추후 안내',
    );
  });
});
