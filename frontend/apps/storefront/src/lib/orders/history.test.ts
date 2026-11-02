import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  appendOrders,
  cancelFailure,
  canResumePayment,
  confirmFailure,
  orderActions,
  orderCancelPath,
  orderListPath,
  orderPurchaseConfirmationPath,
  readOrderPathId,
  statusLabel,
  type OrderSummary,
} from './history';

const t = ko.orders;
const NOW = Date.parse('2026-11-02T01:00:00Z');
const summary = (orderId: string, patch: Partial<OrderSummary> = {}): OrderSummary => ({
  orderId,
  status: { code: 'PENDING_PAYMENT', name: '결제 대기', description: '' },
  orderName: '저고리 외 1건',
  itemCount: 2,
  totalAmount: { amount: 39000, currency: 'KRW' },
  cancelable: true,
  paymentDueAt: '2026-11-02T01:30:00Z',
  createdAt: '2026-11-02T01:00:00Z',
  ...patch,
});
const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  fieldErrors: ConstructorParameters<typeof ApiError>[4] = [],
) => new ApiError(kind, status, code, '서버 문구', fieldErrors);

describe('주문 내역 경로', () => {
  it('목록은 20개씩, 취소는 명사 리소스다', () => {
    expect(orderListPath(1)).toBe('/api/v1/orders?page=1&size=20');
    expect(orderCancelPath('4611686018427387904')).toBe(
      '/api/v1/orders/4611686018427387904/cancellation',
    );
    expect(orderPurchaseConfirmationPath('4611686018427387904')).toBe(
      '/api/v1/orders/4611686018427387904/purchase-confirmation',
    );
  });

  it('주문 아이디 경로 조각은 양의 정수 문자열만 — 정밀도를 잃지 않게 문자열 그대로', () => {
    expect(readOrderPathId('4611686018427387904')).toBe('4611686018427387904');
    expect(readOrderPathId('0')).toBeNull();
    expect(readOrderPathId('-1')).toBeNull();
    expect(readOrderPathId('12a')).toBeNull();
    expect(readOrderPathId('1'.repeat(20))).toBeNull();
  });
});

describe('statusLabel', () => {
  it('국문은 서버 이름, 영문은 코드로 사전에서 — 없는 코드는 서버 이름', () => {
    const status = { code: 'PENDING_PAYMENT', name: '결제 대기', description: '' };
    expect(statusLabel(status, t, 'ko')).toBe('결제 대기');
    expect(statusLabel(status, en.orders, 'en')).toBe('Awaiting payment');
    expect(statusLabel({ ...status, code: 'NEW_STATUS', name: '새 상태' }, en.orders, 'en')).toBe(
      '새 상태',
    );
  });
});

describe('canResumePayment', () => {
  it('결제 대기이고 기한 전일 때만', () => {
    expect(canResumePayment(summary('1'), NOW)).toBe(true);
    expect(canResumePayment(summary('1', { paymentDueAt: '2026-11-02T00:59:59Z' }), NOW)).toBe(
      false,
    );
    expect(canResumePayment(summary('1', { paymentDueAt: 'not-a-date' }), NOW)).toBe(false);
    expect(
      canResumePayment(
        summary('1', { status: { code: 'PAID', name: '결제 완료', description: '' } }),
        NOW,
      ),
    ).toBe(false);
  });
});

describe('appendOrders', () => {
  it('OFFSET 이 밀려 다시 온 주문은 거른다', () => {
    const merged = appendOrders([summary('3'), summary('2')], [summary('2'), summary('1')]);
    expect(merged.map((order) => order.orderId)).toEqual(['3', '2', '1']);
  });
});

describe('cancelFailure', () => {
  it('401 은 로그인 안내', () => {
    expect(cancelFailure(apiError('unauthorized', 401, null), t)).toEqual({ kind: 'signedOut' });
  });

  it('결제 완료 · 만료가 먼저 커밋(409 ORDER_020)이면 사전 문구로 알리고 상세를 다시 읽는다', () => {
    expect(cancelFailure(apiError('conflict', 409, 'ORDER_020'), t)).toEqual({
      kind: 'reload',
      message: t.cancelErrors.ORDER_020,
    });
    expect(cancelFailure(apiError('notFound', 404, 'ORDER_001'), t)).toEqual({
      kind: 'reload',
      message: t.cancelErrors.ORDER_001,
    });
  });

  it('결과를 모르면(502/504 · 5xx · 연결 실패 · 봉투 아님 · 알 수 없는 예외) 다시 읽어 확인한다', () => {
    for (const cause of [
      apiError('unknownOutcome', 504, null),
      apiError('server', 500, 'COMMON_001'),
      apiError('network', null, null),
      apiError('invalidResponse', 200, null),
      new Error('boom'),
    ]) {
      expect(cancelFailure(cause, t)).toEqual({ kind: 'reload', message: t.cancelUnknown });
    }
  });

  it('반영되지 않은 실패는 문구만 — 429 · 503 · 사유 오류', () => {
    expect(cancelFailure(apiError('rateLimited', 429, 'GATEWAY_006'), t)).toEqual({
      kind: 'retry',
      message: t.cancelErrors.GATEWAY_006,
    });
    expect(cancelFailure(apiError('unavailable', 503, 'BFF_002'), t)).toEqual({
      kind: 'retry',
      message: t.cancelFailed,
    });
    // 사유 없음(@NotNull)은 fieldErrors 가 있어 validation 이다
    const noReason = apiError('validation', 400, 'ORDER_154', [
      { field: 'reason', code: 'ORDER_154', message: '서버 문구' },
    ]);
    expect(cancelFailure(noReason, t)).toEqual({
      kind: 'retry',
      message: t.cancelErrors.ORDER_154,
    });
    // 코드 없는 409(같은 멱등 키 처리 중 등)는 경합이 아니다 — 문구만
    expect(cancelFailure(apiError('conflict', 409, null), t)).toEqual({
      kind: 'retry',
      message: t.cancelFailed,
    });
  });

  it('서버 국문 문구를 보이지 않는다(영문 스토어)', () => {
    expect(cancelFailure(apiError('server', 400, 'ORDER_999'), en.orders)).toEqual({
      kind: 'retry',
      message: en.orders.cancelFailed,
    });
  });
});

describe('orderActions', () => {
  const at = (code: string, cancelable = false) =>
    orderActions({ status: { code, name: code, description: '' }, cancelable });

  it('취소는 서버의 cancelable 만 따른다 — 출고 뒤 상태에는 없다', () => {
    expect(at('PENDING_PAYMENT', true).cancel).toBe(true);
    for (const code of ['PAID', 'PREPARING', 'SHIPPING', 'DELIVERED', 'PURCHASE_CONFIRMED']) {
      expect(at(code).cancel).toBe(false);
    }
  });

  it('구매 확정은 배송 완료만 — 이미 확정 · 배송 중 · 반품 · 취소에는 없다', () => {
    expect(at('DELIVERED').confirmPurchase).toBe(true);
    for (const code of [
      'PENDING_PAYMENT',
      'PAID',
      'MAKING',
      'PREPARING',
      'SHIPPING',
      'PURCHASE_CONFIRMED',
      'RETURN_REQUESTED',
      'RETURNED',
      'CANCELED',
      'EXPIRED',
    ]) {
      expect(at(code).confirmPurchase).toBe(false);
    }
  });
});

describe('confirmFailure', () => {
  it('401 은 로그인 안내', () => {
    expect(confirmFailure(apiError('unauthorized', 401, null), t)).toEqual({ kind: 'signedOut' });
  });

  it('배송 완료가 아니거나(ORDER_020) 다른 전이가 먼저(ORDER_025) · 404 면 다시 읽는다', () => {
    for (const code of ['ORDER_020', 'ORDER_025']) {
      expect(confirmFailure(apiError('conflict', 409, code), t)).toEqual({
        kind: 'reload',
        message: t.confirmErrors[code],
      });
    }
    expect(confirmFailure(apiError('notFound', 404, 'ORDER_001'), t)).toEqual({
      kind: 'reload',
      message: t.confirmErrors.ORDER_001,
    });
  });

  it('결과를 모르면 다시 읽어 확인한다(구매 확정은 멱등)', () => {
    for (const cause of [
      apiError('unknownOutcome', 504, null),
      apiError('server', 500, 'COMMON_001'),
      apiError('network', null, null),
      new Error('boom'),
    ]) {
      expect(confirmFailure(cause, t)).toEqual({ kind: 'reload', message: t.confirmUnknown });
    }
  });

  it('반영되지 않은 실패는 문구만 — 429 · 503 · 그 밖의 4xx(영문은 사전 문구)', () => {
    expect(confirmFailure(apiError('rateLimited', 429, 'GATEWAY_006'), t)).toEqual({
      kind: 'retry',
      message: t.confirmErrors.GATEWAY_006,
    });
    expect(confirmFailure(apiError('unavailable', 503, 'BFF_002'), t)).toEqual({
      kind: 'retry',
      message: t.confirmFailed,
    });
    expect(confirmFailure(apiError('server', 400, 'ORDER_141'), en.orders)).toEqual({
      kind: 'retry',
      message: en.orders.confirmFailed,
    });
  });
});
