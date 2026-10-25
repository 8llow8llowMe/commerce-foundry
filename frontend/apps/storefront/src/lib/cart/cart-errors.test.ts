import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { getDictionary } from '@/lib/i18n/dictionaries';
import { cartErrorText } from './cart-errors';

describe('cartErrorText', () => {
  it('CART_115(맞춤 입력의 제어 문자)는 사전 문구로 보인다 — 서버 메시지를 그대로 쓰지 않는다', () => {
    const error = new ApiError('validation', 400, 'CART_115', '서버 메시지', [
      { code: 'CART_115', field: 'customOptions[0].value', message: '서버 메시지' },
    ]);
    for (const locale of ['ko', 'en'] as const) {
      const t = getDictionary(locale).cart;
      expect(t.errors.CART_115).toBeTruthy();
      expect(cartErrorText(error, t)).toBe(t.errors.CART_115);
    }
  });

  it('모르는 코드는 기본 실패 문구다', () => {
    const t = getDictionary('ko').cart;
    expect(cartErrorText(new ApiError('server', 500, 'CART_999', null), t)).toBe(t.failed);
  });
});
