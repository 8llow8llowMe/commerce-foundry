import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { readShippingPolicy, shippingNotice } from './shipping-policy';

const krw = (amount: number) => ({ amount, currency: 'KRW' });

const DOMESTIC = {
  baseFee: krw(3000),
  freeShippingThreshold: krw(50000),
  remoteAreas: [
    { name: '제주', postalCodeFrom: '63000', postalCodeTo: '63644', surcharge: krw(3000) },
    { name: '도서', postalCodeFrom: '23000', postalCodeTo: '23010', surcharge: krw(5000) },
  ],
};

describe('readShippingPolicy', () => {
  it('정책이 없으면(null) · 기본 배송비 모양이 다르면 null — 틀린 금액을 보이지 않는다', () => {
    expect(readShippingPolicy(null, false)).toBeNull();
    expect(readShippingPolicy({ baseFee: { amount: '3000', currency: 'KRW' } }, false)).toBeNull();
    expect(
      readShippingPolicy({ baseFee: krw(3000), freeShippingThreshold: { amount: 1 } }, false),
    ).toBeNull();
  });

  it('모양이 다른 도서산간 줄만 버린다', () => {
    const policy = readShippingPolicy(
      {
        ...DOMESTIC,
        remoteAreas: [...DOMESTIC.remoteAreas, { name: '', surcharge: krw(1) }, null],
      },
      false,
    );
    expect(policy?.remoteAreas.map((area) => area.name)).toEqual(['제주', '도서']);
  });
});

describe('shippingNotice', () => {
  it('국내: 기본 배송비 · 무료배송 기준 · 도서산간을 서버 금액 그대로 안내한다', () => {
    const notice = shippingNotice(readShippingPolicy(DOMESTIC, false)!, 'ko', ko.shipping);
    expect(notice.lines).toEqual([
      '배송비 3,000원',
      '50,000원 이상 구매 시 무료배송',
      '제주 · 도서산간 지역은 추가 배송비가 있습니다(제주 3,000원, 도서 5,000원). 무료배송이어도 붙습니다.',
    ]);
    expect(notice.freeShipping).toBe('50,000원 이상 구매 시 무료배송');
  });

  it('기본 배송비가 0 이면 무료배송 한 줄이고 무료배송 기준은 안내하지 않는다', () => {
    const policy = readShippingPolicy(
      { baseFee: krw(0), freeShippingThreshold: krw(50000) },
      false,
    )!;
    expect(shippingNotice(policy, 'ko', ko.shipping)).toEqual({
      lines: ['무료배송'],
      freeShipping: null,
    });
  });

  it('무료배송 기준이 없으면 장바구니 안내도 없다', () => {
    const policy = readShippingPolicy({ baseFee: krw(3000), freeShippingThreshold: null }, false)!;
    expect(shippingNotice(policy, 'ko', ko.shipping)).toEqual({
      lines: ['배송비 3,000원'],
      freeShipping: null,
    });
  });

  it('해외: 기본료는 "부터", 관세 안내 문구를 덧붙이고 도서산간은 보지 않는다', () => {
    const policy = readShippingPolicy(
      {
        baseFee: { amount: 1500, currency: 'USD' },
        freeShippingThreshold: { amount: 30000, currency: 'USD' },
        customsNotice: ' Import duties are paid by the recipient. ',
        remoteAreas: DOMESTIC.remoteAreas,
        zones: [],
      },
      true,
    )!;
    expect(shippingNotice(policy, 'en', en.shipping).lines).toEqual([
      'International shipping from $15.00 (plus a weight-based rate)',
      'Free shipping on orders of $300.00 or more',
      'Import duties are paid by the recipient.',
    ]);
  });
});
