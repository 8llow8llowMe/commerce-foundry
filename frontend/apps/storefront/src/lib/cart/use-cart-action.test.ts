import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { cartActionResult } from './use-cart-action';

const t = ko.cart;

describe('cartActionResult', () => {
  it('담기면 안내와 장바구니 링크', () => {
    expect(cartActionResult({ status: 'added' }, t)).toEqual({ text: t.added, showCartLink: true });
  });

  it('서버 코드는 사전 문구로 — 영문 스토어는 영문', () => {
    const error = new ApiError('conflict', 409, 'CART_003', '서버 문구');
    expect(cartActionResult({ status: 'failed', error, addedCount: 0 }, t)).toEqual({
      text: '지금은 구매할 수 없는 상품입니다.',
      showCartLink: false,
    });
    expect(cartActionResult({ status: 'failed', error, addedCount: 0 }, en.cart).text).toBe(
      'This item is not available right now.',
    );
  });

  it('429 는 요청 제한 문구, 모르는 오류는 다시 시도 문구', () => {
    const limited = new ApiError('rateLimited', 429, 'GATEWAY_006', null, [], 3);
    expect(cartActionResult({ status: 'failed', error: limited, addedCount: 0 }, t).text).toBe(
      t.errors.GATEWAY_006,
    );
    expect(
      cartActionResult({ status: 'failed', error: new Error('x'), addedCount: 0 }, t).text,
    ).toBe(t.failed);
  });

  it('여러 줄 중 일부만 담겼으면 담긴 수와 이유를 함께, 장바구니 링크도 보인다', () => {
    const error = new ApiError('conflict', 409, 'CART_005', null);
    expect(cartActionResult({ status: 'failed', error, addedCount: 1 }, t)).toEqual({
      text: '1개 상품은 담았습니다. 한 상품에 담을 수 있는 최대 수량을 넘었습니다.',
      showCartLink: true,
    });
  });

  it('비회원 50줄 · 저장소 없음', () => {
    expect(cartActionResult({ status: 'lineLimit' }, t).text).toBe(t.lineLimit);
    expect(cartActionResult({ status: 'storageUnavailable' }, t)).toEqual({
      text: t.storageUnavailable,
      showCartLink: false,
    });
  });
});
