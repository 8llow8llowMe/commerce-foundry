import { formatMoney } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import type { ShippingQuote } from '@/lib/checkout/quote';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { shippingNotes } from './QuoteSummary';

const won = (amount: number) => ({ amount, currency: 'KRW' as const });
const domestic: ShippingQuote = {
  scope: 'DOMESTIC',
  baseFee: won(3000),
  freeShippingApplied: false,
  remoteAreaSurcharge: won(0),
  remoteAreaName: null,
  freeShippingThreshold: won(50000),
  weightFee: null,
  shippingZoneName: null,
};
const notes = (shipping: ShippingQuote) =>
  shippingNotes(shipping, ko.checkout, (money) => formatMoney(money));

describe('shippingNotes', () => {
  it('기본 배송비와 아직 못 넘은 무료배송 기준을 보인다', () => {
    expect(notes(domestic)).toEqual(['기본 배송비 3,000원', '50,000원 이상 무료배송']);
  });

  it('무료배송이면 기본 배송비 · 기준 대신 무료배송, 도서산간 추가금은 그대로 붙는다', () => {
    expect(
      notes({
        ...domestic,
        freeShippingApplied: true,
        remoteAreaSurcharge: won(3000),
        remoteAreaName: '제주',
      }),
    ).toEqual(['무료배송', '도서산간 추가 3,000원 (제주)']);
  });

  it('해외는 무게 요금과 국가 그룹 이름을 보인다', () => {
    expect(
      notes({
        ...domestic,
        scope: 'INTERNATIONAL',
        baseFee: { amount: 1500, currency: 'USD' },
        freeShippingThreshold: null,
        weightFee: { amount: 850, currency: 'USD' },
        shippingZoneName: '아시아',
      }),
    ).toEqual(['기본 배송비 $15.00', '무게 요금 $8.50 (아시아)']);
  });
});
