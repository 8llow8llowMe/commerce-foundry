import { describe, expect, it } from 'vitest';
import { LIST_PRICE_NOT_HIGHER, PRICE_REQUIRED } from './create';
import { pricingFormOf, pricingRequest, samePricing } from './pricing';
import { adminProduct } from './test-fixtures';

describe('가격 수정', () => {
  it('상세 금액을 입력 칸 표기로 채운다(USD 는 소수 둘째 자리)', () => {
    expect(pricingFormOf(adminProduct())).toEqual({ salePrice: '129000', listPrice: '159000' });
    expect(
      pricingFormOf(
        adminProduct({ salePrice: { amount: 8950, currency: 'USD' }, listPrice: null }),
      ),
    ).toEqual({ salePrice: '89.50', listPrice: '' });
  });

  it('정가를 비우면 null 로 지우고 통화는 상품 통화다', () => {
    expect(pricingRequest({ salePrice: '99,000', listPrice: '' }, 'KRW', 3)).toEqual({
      ok: true,
      request: { version: 3, salePrice: { amount: 99000, currency: 'KRW' }, listPrice: null },
    });
  });

  it('판매가 필수, 정가는 판매가보다 높아야 한다', () => {
    expect(pricingRequest({ salePrice: '', listPrice: '' }, 'KRW', 0)).toEqual({
      ok: false,
      errors: { salePrice: [PRICE_REQUIRED] },
    });
    expect(pricingRequest({ salePrice: '100', listPrice: '100' }, 'KRW', 0)).toEqual({
      ok: false,
      errors: { listPrice: [LIST_PRICE_NOT_HIGHER] },
    });
  });

  it('앞뒤 공백만 다르면 같다', () => {
    expect(
      samePricing({ salePrice: ' 1 ', listPrice: '' }, { salePrice: '1', listPrice: '' }),
    ).toBe(true);
  });
});
