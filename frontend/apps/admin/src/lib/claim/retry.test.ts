import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  REFUND_KEY_VALIDITY_MS,
  RETRY_FAILED,
  RETRY_UNKNOWN,
  refundDeadlineOf,
  retryFailure,
} from './retry';
import type { ClaimDetail } from './types';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null = null,
) => new ApiError(kind, status, code, message, []);

const claim = (type: string, situation: string, requestedAt = '2026-11-01T00:00:00Z') =>
  ({
    claimId: '1',
    orderId: '2',
    type: { code: type, name: type, description: '' },
    status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
    expectedRefund: null,
    requestedAt,
    items: [],
    recovery: {
      situation: { code: situation, name: situation, description: '' },
      nextAutomaticAttemptAt: null,
      retryable: true,
      refundConfirmable: false,
      completable: false,
      lastDecision: null,
      version: 3,
    },
  }) satisfies ClaimDetail;

describe('retryFailure', () => {
  it('401 · 403 은 AccessNotice 로 안내한다', () => {
    expect(retryFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(retryFailure(apiError('forbidden', 403, 'SECURITY_005'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('버전 불일치 · 다시 시도 불가 · 자동 처리 중 · 결제 취소 결과 미반영 · 없는 접수는 서버 문구를 두고 다시 읽는다', () => {
    for (const [kind, status, code] of [
      ['conflict', 409, 'ORDER_037'],
      ['conflict', 409, 'ORDER_038'],
      ['conflict', 409, 'ORDER_039'],
      ['unavailable', 503, 'ORDER_036'],
      ['notFound', 404, 'ORDER_032'],
    ] as const) {
      expect(retryFailure(apiError(kind, status, code, `서버 ${code}`))).toEqual({
        kind: 'inline',
        message: `서버 ${code}`,
        reload: true,
      });
    }
  });

  it('자동 처리 꺼짐(ORDER_040)은 서버 문구만 두고 다시 읽지 않는다', () => {
    expect(retryFailure(apiError('unavailable', 503, 'ORDER_040', '꺼짐'))).toEqual({
      kind: 'inline',
      message: '꺼짐',
      reload: false,
    });
  });

  it('결과 모름(502 · 504)은 상세를 다시 읽게 한다', () => {
    expect(retryFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'inline',
      message: RETRY_UNKNOWN,
      reload: true,
    });
  });

  it('그 밖의 4xx 는 서버 문구, 연결 실패 · 5xx · 알 수 없는 오류는 공통 문구로 다시 읽는다', () => {
    expect(retryFailure(apiError('validation', 400, 'ORDER_169', '버전 필요'))).toEqual({
      kind: 'inline',
      message: '버전 필요',
      reload: true,
    });
    for (const cause of [
      apiError('network', null, null),
      apiError('server', 500, 'COMMON_001', '서버 오류'),
      new Error('boom'),
    ]) {
      expect(retryFailure(cause)).toEqual({ kind: 'inline', message: RETRY_FAILED, reload: true });
    }
  });
});

describe('refundDeadlineOf', () => {
  const requested = Date.parse('2026-11-01T00:00:00Z');

  it('결제 후 취소의 환불 미확정에서만 접수 + 14일 기한을 준다', () => {
    const deadline = refundDeadlineOf(claim('CANCELLATION', 'REFUND_UNCONFIRMED'), requested);
    expect(deadline?.at.toISOString()).toBe('2026-11-15T00:00:00.000Z');
    expect(deadline?.remaining).toBe('14일 남음');
    expect(
      refundDeadlineOf(claim('CANCELLATION', 'RESTORATION_UNCONFIRMED'), requested),
    ).toBeNull();
    expect(refundDeadlineOf(claim('CANCELLATION', 'REFUND_REJECTED'), requested)).toBeNull();
    expect(refundDeadlineOf(claim('RETURN', 'REFUND_UNCONFIRMED'), requested)).toBeNull();
  });

  it('남은 시간은 일 · 시간 단위로 보이고 지나면 null 이다', () => {
    const target = claim('CANCELLATION', 'REFUND_UNCONFIRMED');
    const at = requested + REFUND_KEY_VALIDITY_MS;
    const hour = 60 * 60 * 1000;
    expect(refundDeadlineOf(target, at - 26 * hour)?.remaining).toBe('1일 2시간 남음');
    expect(refundDeadlineOf(target, at - 5 * hour)?.remaining).toBe('5시간 남음');
    expect(refundDeadlineOf(target, at - 10 * 60 * 1000)?.remaining).toBe('1시간 미만 남음');
    expect(refundDeadlineOf(target, at)?.remaining).toBeNull();
  });

  it('읽을 수 없는 접수 시각이면 기한을 보이지 않는다', () => {
    expect(
      refundDeadlineOf(claim('CANCELLATION', 'REFUND_UNCONFIRMED', 'x'), requested),
    ).toBeNull();
  });
});
