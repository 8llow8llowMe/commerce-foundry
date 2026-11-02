import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  confirmFailure,
  confirmKeyFor,
  failText,
  methodLabel,
  PAYMENT_RETRY_AFTER_SEC,
  paymentOutcome,
  paymentPageHref,
  paymentReturnUrls,
  POLL_DELAYS_SEC,
  prepareFailure,
  readFailReturn,
  readOrderId,
  readSuccessReturn,
  requestFailure,
  sdkErrorCode,
  tossMessage,
  widgetClientKey,
  type KeyStorage,
} from './payment';

const t = ko.payment;
const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  retryAfterSec: number | null = null,
) => new ApiError(kind, status, code, null, [], retryAfterSec);
const status = (code: string) => ({ status: { code, name: code, description: '' } });

describe('주소 · 쿼리', () => {
  it('주문 아이디는 양의 정수 문자열만 받는다', () => {
    expect(readOrderId('4611686018427387905')).toBe('4611686018427387905');
    expect(readOrderId(['12', '13'])).toBe('12');
    for (const bad of [undefined, '', '0', '012', '-1', '1.5', 'abc', '12345678901234567890']) {
      expect(readOrderId(bad)).toBeNull();
    }
  });

  it('결제위젯 키만 받고 API 개별 연동 키 · 시크릿 키 · 빈 값은 버린다', () => {
    expect(widgetClientKey('test_gck_example_key')).toBe('test_gck_example_key');
    expect(widgetClientKey(' live_gck_abc ')).toBe('live_gck_abc');
    for (const bad of [undefined, '', 'test_ck_abc', 'test_gsk_abc', 'live_sk_abc', 'gck_abc']) {
      expect(widgetClientKey(bad)).toBeNull();
    }
  });

  it('결제 화면 경로와 복귀 주소 — 지금 origin + 언어 접두어 + 주문 아이디', () => {
    expect(paymentPageHref('42')).toBe('/checkout/payment?orderId=42');
    expect(paymentReturnUrls('https://shop.test', '', '42')).toEqual({
      successUrl: 'https://shop.test/checkout/payment/success?order=42',
      failUrl: 'https://shop.test/checkout/payment/fail?order=42',
    });
    expect(paymentReturnUrls('https://shop.test', '/en', '42').successUrl).toBe(
      'https://shop.test/en/checkout/payment/success?order=42',
    );
  });

  it('성공 복귀 쿼리를 승인 본문으로 — 금액은 양의 정수, KRW', () => {
    expect(
      readSuccessReturn({
        paymentKey: 'pk_1',
        orderId: 'PG-order_01',
        amount: '39000',
        order: '7',
      }),
    ).toEqual({
      confirm: {
        paymentKey: 'pk_1',
        pgOrderId: 'PG-order_01',
        amount: { amount: 39000, currency: 'KRW' },
      },
      orderId: '7',
    });
    expect(
      readSuccessReturn({ paymentKey: 'pk', orderId: '123456', amount: '1', order: 'x' })?.orderId,
    ).toBeNull();
  });

  it('성공 복귀 쿼리가 어긋나면 null — 승인을 보내지 않는다', () => {
    const ok = { paymentKey: 'pk', orderId: '123456', amount: '100' };
    expect(readSuccessReturn({ ...ok, paymentKey: undefined })).toBeNull();
    expect(readSuccessReturn({ ...ok, paymentKey: 'x'.repeat(201) })).toBeNull();
    expect(readSuccessReturn({ ...ok, orderId: '12345' })).toBeNull();
    expect(readSuccessReturn({ ...ok, orderId: 'a'.repeat(65) })).toBeNull();
    expect(readSuccessReturn({ ...ok, orderId: 'abc 123' })).toBeNull();
    for (const amount of ['0', '-1', '1.5', '1e3', '0100', '9'.repeat(16), '']) {
      expect(readSuccessReturn({ ...ok, amount })).toBeNull();
    }
  });

  it('실패 복귀 쿼리 — 코드는 형식이 맞을 때만, 쿼리 message 는 읽지 않는다(지어낸 링크의 임의 문구)', () => {
    expect(
      readFailReturn({
        code: 'PAY_PROCESS_CANCELED',
        message: '고객센터로 입금하세요',
        order: '9',
      }),
    ).toEqual({ code: 'PAY_PROCESS_CANCELED', orderId: '9' });
    expect(readFailReturn({ code: '<script>', message: '  ' })).toEqual({
      code: null,
      orderId: null,
    });
    expect(tossMessage('x'.repeat(300))).toHaveLength(200);
  });
});

describe('실패 화면 문구', () => {
  it('사용자 취소는 취소 문구, 그 밖은 일반 문구 — 사전 문구만 쓴다', () => {
    expect(failText({ code: 'PAY_PROCESS_CANCELED', orderId: null }, t)).toBe(t.failCanceled);
    const rejected = { code: 'REJECT_CARD_COMPANY', orderId: '1' };
    expect(failText(rejected, t)).toBe(t.failGeneric);
    expect(failText(rejected, en.payment)).toBe(en.payment.failGeneric);
  });

  it('결제창 요청 실패 — SDK 사용자 취소는 안내, 그 밖은 국문 SDK 메시지 · 범용 문구', () => {
    const cancel = Object.assign(new Error('취소'), { code: 'USER_CANCEL' });
    expect(sdkErrorCode(cancel)).toBe('USER_CANCEL');
    expect(sdkErrorCode('x')).toBeNull();
    expect(requestFailure(cancel, t, 'ko')).toEqual({ canceled: true, message: t.canceled });
    const other = Object.assign(new Error('카드사를 선택해 주세요.'), { code: 'NEED_CARD' });
    expect(requestFailure(other, t, 'ko')).toEqual({
      canceled: false,
      message: '카드사를 선택해 주세요.',
    });
    expect(requestFailure(other, en.payment, 'en').message).toBe(en.payment.requestFailed);
    expect(requestFailure({}, t, 'ko').message).toBe(t.requestFailed);
  });
});

describe('결제 상태', () => {
  it('상태 코드로 화면을 가른다', () => {
    expect(paymentOutcome(status('READY'))).toBe('ready');
    expect(paymentOutcome(status('DONE'))).toBe('done');
    expect(paymentOutcome(status('FAILED'))).toBe('failed');
    expect(paymentOutcome(status('UNKNOWN'))).toBe('pending');
    expect(paymentOutcome(status('IN_PROGRESS'))).toBe('pending');
    expect(paymentOutcome(status('ABORTED'))).toBe('other');
  });

  it('결제수단 — 간편결제 제공자를 붙인다', () => {
    const card = { code: 'CARD', name: '카드', description: '' };
    expect(methodLabel({ method: null, easyPayProvider: null })).toBeNull();
    expect(methodLabel({ method: card, easyPayProvider: null })).toBe('카드');
    expect(methodLabel({ method: card, easyPayProvider: '카카오페이' })).toBe('카드 (카카오페이)');
  });

  it('미확정 조회 간격은 1분 안이다', () => {
    expect(POLL_DELAYS_SEC.reduce((sum, sec) => sum + sec, 0)).toBeLessThanOrEqual(60);
  });
});

describe('prepareFailure', () => {
  const fail = (cause: unknown) => prepareFailure(cause, t);

  it('401 은 로그인 안내', () => {
    expect(fail(apiError('unauthorized', 401, 'SECURITY_001'))).toEqual({ kind: 'signedOut' });
  });

  it('주문 상태 · 통화 · 기한은 끝 — 코드별 문구', () => {
    for (const [s, code] of [
      [404, 'PAYMENT_002'],
      [409, 'PAYMENT_003'],
      [409, 'PAYMENT_004'],
      [409, 'PAYMENT_005'],
    ] as const) {
      const kind = s === 404 ? 'notFound' : 'conflict';
      expect(fail(apiError(kind, s, code))).toEqual({
        kind: 'final',
        message: t.prepareErrors[code],
      });
    }
  });

  it('결제 게이트가 닫히면 다시 시도 없이 결제 불가 안내', () => {
    expect(fail(apiError('unavailable', 503, 'PAYMENT_015'))).toEqual({
      kind: 'final',
      message: t.unavailable,
    });
  });

  it('요청 겹침 · 일시 장애 · 5xx · 연결 실패 · 429 는 다시 시도', () => {
    expect(fail(apiError('conflict', 409, 'PAYMENT_012'))).toEqual({
      kind: 'retry',
      message: t.prepareErrors.PAYMENT_012,
    });
    expect(fail(apiError('unavailable', 503, 'PAYMENT_011'))).toEqual({
      kind: 'retry',
      message: t.prepareErrors.PAYMENT_011,
    });
    expect(fail(apiError('server', 500, 'COMMON_001'))).toEqual({
      kind: 'retry',
      message: t.prepareFailed,
    });
    expect(fail(apiError('network', null, null)).kind).toBe('retry');
    expect(fail(apiError('rateLimited', 429, 'GATEWAY_006', 4))).toEqual({
      kind: 'retry',
      message: t.prepareErrors.GATEWAY_006,
    });
    expect(fail(new TypeError('x')).kind).toBe('retry');
  });

  it('그 밖의 4xx 는 결제할 수 없는 주문', () => {
    expect(fail(apiError('server', 400, 'PAYMENT_102'))).toEqual({
      kind: 'final',
      message: t.invalidOrder,
    });
  });
});

describe('confirmFailure', () => {
  const fail = (cause: unknown) => confirmFailure(cause, t);

  it('401 은 로그인 안내', () => {
    expect(fail(apiError('unauthorized', 401, null))).toEqual({ kind: 'signedOut' });
  });

  it('결제사 연결 실패(청구 없음)는 같은 키로 다시 확인 — 계약의 Retry-After 동안 막는다', () => {
    expect(fail(apiError('unavailable', 503, 'PAYMENT_010'))).toEqual({
      kind: 'recheck',
      message: t.confirmErrors.PAYMENT_010,
      retryAfterSec: PAYMENT_RETRY_AFTER_SEC,
    });
  });

  it('처리 중 · 기록 실패 · 502/504 · 연결 실패 · 봉투 아님은 같은 키로 다시 확인', () => {
    expect(fail(apiError('conflict', 409, 'PAYMENT_007'))).toEqual({
      kind: 'recheck',
      message: t.confirmErrors.PAYMENT_007,
      retryAfterSec: null,
    });
    expect(fail(apiError('server', 500, 'PAYMENT_013')).kind).toBe('recheck');
    expect(fail(apiError('unavailable', 503, 'PAYMENT_011')).kind).toBe('recheck');
    expect(fail(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'recheck',
      message: t.confirmRetry,
      retryAfterSec: null,
    });
    expect(fail(apiError('network', null, null)).kind).toBe('recheck');
    expect(fail(apiError('invalidResponse', 200, null)).kind).toBe('recheck');
    expect(fail(new Error('boom')).kind).toBe('recheck');
  });

  it('429 는 다시 확인 + Retry-After', () => {
    expect(fail(apiError('rateLimited', 429, 'GATEWAY_006', 7))).toEqual({
      kind: 'recheck',
      message: t.prepareErrors.GATEWAY_006,
      retryAfterSec: 7,
    });
  });

  it('끝난 결제 시도는 다시 결제(새 준비)', () => {
    expect(fail(apiError('conflict', 409, 'PAYMENT_006'))).toEqual({
      kind: 'repay',
      message: t.confirmErrors.PAYMENT_006,
    });
  });

  it('금액 · 결제키 · 키 재사용 불일치는 다시 결제 없이 고객센터 안내', () => {
    for (const [kind, s, code] of [
      ['server', 400, 'PAYMENT_008'],
      ['server', 400, 'PAYMENT_009'],
      ['conflict', 409, 'PAYMENT_014'],
    ] as const) {
      expect(fail(apiError(kind, s, code))).toEqual({ kind: 'mismatch', message: t.mismatch });
    }
  });

  it('승인 직전 주문 재확인 실패는 장바구니로', () => {
    expect(fail(apiError('notFound', 404, 'PAYMENT_002'))).toEqual({
      kind: 'final',
      message: t.confirmErrors.PAYMENT_002,
      cartLink: true,
    });
    expect(fail(apiError('conflict', 409, 'PAYMENT_004')).kind).toBe('final');
    expect(fail(apiError('conflict', 409, 'PAYMENT_012'))).toEqual({
      kind: 'final',
      message: t.invalidOrder,
      cartLink: true,
    });
  });

  it('결제 정보 없음 · 게이트 닫힘 · 그 밖의 4xx 는 끝(장바구니 링크 없음)', () => {
    expect(fail(apiError('notFound', 404, 'PAYMENT_001'))).toEqual({
      kind: 'final',
      message: t.confirmErrors.PAYMENT_001,
      cartLink: false,
    });
    expect(fail(apiError('unavailable', 503, 'PAYMENT_015'))).toEqual({
      kind: 'final',
      message: t.unavailable,
      cartLink: false,
    });
    expect(fail(apiError('server', 400, 'PAYMENT_114'))).toEqual({
      kind: 'final',
      message: t.invalidReturn,
      cartLink: false,
    });
  });
});

describe('confirmKeyFor', () => {
  const uuid = (n: number) => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  const memoryStorage = (): KeyStorage & { data: Map<string, string> } => {
    const data = new Map<string, string>();
    return {
      data,
      getItem: (name) => data.get(name) ?? null,
      setItem: (name, value) => void data.set(name, value),
    };
  };

  it('결제 시도마다 키를 하나 만들어 두고 다시 쓴다 — 저장 값은 키뿐이다', () => {
    const storage = memoryStorage();
    const create = vi.fn().mockReturnValueOnce(uuid(1)).mockReturnValueOnce(uuid(2));
    expect(confirmKeyFor('PG000001', storage, create)).toBe(uuid(1));
    expect(confirmKeyFor('PG000001', storage, create)).toBe(uuid(1));
    expect([...storage.data]).toEqual([['cf.pay.confirm.PG000001', uuid(1)]]);
    expect(confirmKeyFor('PG000002', storage, create)).toBe(uuid(2));
    expect(create).toHaveBeenCalledTimes(2);
  });

  it('새로 고친 탭(저장소에 키가 있다)은 저장된 키를 쓴다. UUID 가 아니면 새로 만든다', () => {
    const storage = memoryStorage();
    storage.data.set('cf.pay.confirm.PG000003', uuid(3));
    expect(confirmKeyFor('PG000003', storage, () => uuid(9))).toBe(uuid(3));
    storage.data.set('cf.pay.confirm.PG000004', 'not-a-uuid');
    expect(confirmKeyFor('PG000004', storage, () => uuid(4))).toBe(uuid(4));
  });

  it('저장소가 없거나 실패하면 이 탭의 메모리로 같은 키다', () => {
    const create = vi.fn().mockReturnValueOnce(uuid(5));
    expect(confirmKeyFor('PG000005', null, create)).toBe(uuid(5));
    expect(confirmKeyFor('PG000005', null, create)).toBe(uuid(5));
    const broken: KeyStorage = {
      getItem: () => {
        throw new Error('blocked');
      },
      setItem: () => {
        throw new Error('blocked');
      },
    };
    expect(confirmKeyFor('PG000006', broken, () => uuid(6))).toBe(uuid(6));
    expect(confirmKeyFor('PG000006', broken, () => uuid(7))).toBe(uuid(6));
    expect(create).toHaveBeenCalledTimes(1);
  });
});
