import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  adjustForm,
  adjustmentFailure,
  adjustmentRequest,
  NOTHING_CHANGED,
  QUANTITY_FORMAT_ERROR,
  skuLabel,
} from './adjustment';
import type { ProductForStock, StockAdmin } from './types';

const STOCK: StockAdmin = {
  skuId: '901',
  stockManaged: true,
  availableQuantity: 5,
  reservedQuantity: 2,
  updatedAt: '2026-10-21T01:00:00Z',
};

describe('skuLabel', () => {
  const product: ProductForStock = {
    productId: '1',
    name: '원피스',
    optionGroups: [
      { optionGroupId: 'g1', name: '색상', values: [{ optionValueId: 'v1', name: '아이보리' }] },
      { optionGroupId: 'g2', name: '사이즈', values: [{ optionValueId: 'v2', name: 'M' }] },
    ],
    skus: [],
  };

  it('옵션 값 이름을 그룹 순서로 잇고, 옵션 없는 SKU 는 기본이다', () => {
    expect(skuLabel(product, ['v1', 'v2'])).toBe('아이보리 / M');
    expect(skuLabel(product, [])).toBe('기본');
  });
});

describe('adjustmentRequest', () => {
  it('수량을 바꾸면 결과 수량과 화면에서 본 수량(기대 수량)을 함께 보낸다 — 계산하지 않는다', () => {
    const form = {
      ...adjustForm(STOCK),
      quantity: '12',
      reason: 'RESTOCK' as const,
      memo: ' 입고 ',
    };
    expect(adjustmentRequest(STOCK, form)).toEqual({
      ok: true,
      request: { quantity: 12, expectedQuantity: 5, reason: 'RESTOCK', memo: '입고' },
    });
  });

  it('무제한으로 바꾸면 관리 여부만 보낸다', () => {
    const form = {
      ...adjustForm(STOCK),
      stockManaged: false,
      reason: 'MANAGEMENT_CHANGE' as const,
    };
    expect(adjustmentRequest(STOCK, form)).toEqual({
      ok: true,
      request: { stockManaged: false, reason: 'MANAGEMENT_CHANGE', memo: null },
    });
  });

  it('무제한 SKU 의 폼은 수량 칸이 비어 있다(숨은 옛 수량을 보이지 않는다)', () => {
    expect(adjustForm({ ...STOCK, stockManaged: false }).quantity).toBe('');
  });

  it('무제한 → 관리로 바꾸면 입력한 수량도 함께 보낸다(같은 숫자여도)', () => {
    const unlimited = { ...STOCK, stockManaged: false, availableQuantity: 0 };
    const form = {
      ...adjustForm(unlimited),
      stockManaged: true,
      quantity: '0',
      reason: 'OTHER' as const,
    };
    expect(adjustmentRequest(unlimited, form)).toEqual({
      ok: true,
      request: {
        stockManaged: true,
        quantity: 0,
        expectedQuantity: 0,
        reason: 'OTHER',
        memo: null,
      },
    });
  });

  it('사유가 비면 null 로 보내 서버 검증에 맡긴다', () => {
    const built = adjustmentRequest(STOCK, { ...adjustForm(STOCK), quantity: '6' });
    expect(built.ok && built.request.reason).toBeNull();
  });

  it('바꾼 값이 없거나 수량 형식이 틀리면 보내지 않는다', () => {
    expect(adjustmentRequest(STOCK, adjustForm(STOCK))).toEqual({
      ok: false,
      errors: { form: [NOTHING_CHANGED] },
    });
    for (const quantity of ['', '-1', '1.5', '열개']) {
      expect(adjustmentRequest(STOCK, { ...adjustForm(STOCK), quantity })).toEqual({
        ok: false,
        errors: { quantity: [QUANTITY_FORMAT_ERROR] },
      });
    }
  });
});

describe('adjustmentFailure', () => {
  it('401 은 폼을 지키고 다시 로그인 안내, 403 은 권한 안내, 409 PRODUCT_020 은 다시 읽기, 502 · 504 는 결과 모름이다', () => {
    expect(adjustmentFailure(new ApiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(adjustmentFailure(new ApiError('forbidden', 403, 'GATEWAY_002', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(adjustmentFailure(new ApiError('conflict', 409, 'PRODUCT_020', null))).toEqual({
      kind: 'conflict',
    });
    expect(adjustmentFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'unknownOutcome',
    });
  });

  it('칸 검증 오류는 그 칸에, 클래스 수준 검증은 폼 전체 오류다', () => {
    const cause = new ApiError('validation', 400, 'PRODUCT_108', null, [
      { code: 'PRODUCT_108', field: 'reason', message: '조정 사유는 필수입니다.' },
      { code: 'PRODUCT_110', field: 'targetPresent', message: '바꿀 값이 없습니다.' },
    ]);
    expect(adjustmentFailure(cause)).toEqual({
      kind: 'errors',
      errors: { reason: ['조정 사유는 필수입니다.'], form: ['바꿀 값이 없습니다.'] },
    });
  });

  it('404 PRODUCT_002 는 서버 문구, 모르는 오류 · 연결 실패는 한 문구다', () => {
    expect(
      adjustmentFailure(new ApiError('notFound', 404, 'PRODUCT_002', 'SKU 를 찾을 수 없습니다.')),
    ).toEqual({ kind: 'errors', errors: { form: ['SKU 를 찾을 수 없습니다.'] } });
    for (const cause of [new ApiError('server', 500, 'X', '내부'), new Error('boom')]) {
      expect(adjustmentFailure(cause)).toEqual({ kind: 'errors', errors: { form: [SAVE_FAILED] } });
    }
  });
});
