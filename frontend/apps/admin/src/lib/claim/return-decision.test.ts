import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { REASON_INVALID_CHARACTER, REASON_REQUIRED, REASON_TOO_LONG } from './decision';
import {
  DEDUCTION_INVALID,
  FAULT_REQUIRED,
  POLICY_NOT_PAYABLE,
  REFUND_NOT_PAYABLE,
  RESTOCK_REQUIRED,
  RETURN_FAILED,
  RETURN_RATE_LIMITED,
  RETURN_SESSION_EXPIRED,
  RETURN_UNKNOWN,
  approvalOutcome,
  approvalPlan,
  confirmStillValid,
  deductionOf,
  deductionUnitLabel,
  expectedRefundOf,
  failureTransition,
  faultOptions,
  initialFault,
  returnActionPath,
  returnFailure,
  returnPanelMode,
} from './return-decision';
import type { ClaimDetail, ReturnDecision } from './types';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null = null,
) => new ApiError(kind, status, code, message, []);

const fault = (code: string, name: string) => ({ code, name, description: `${name} 설명` });

const decision = (overrides: Partial<ReturnDecision> = {}): ReturnDecision => ({
  approvable: true,
  rejectable: true,
  reason: null,
  defaultFault: fault('SELLER', '판매자 귀책'),
  refundByFault: [
    { fault: fault('CUSTOMER', '고객 귀책'), amount: { amount: 90000, currency: 'KRW' } },
    { fault: fault('SELLER', '판매자 귀책'), amount: { amount: 96000, currency: 'KRW' } },
  ],
  ...overrides,
});

const claim = (overrides: Partial<ClaimDetail> = {}): ClaimDetail => ({
  claimId: '7',
  orderId: '2',
  type: { code: 'RETURN', name: '반품', description: '' },
  status: { code: 'REQUESTED', name: '접수', description: '' },
  expectedRefund: null,
  requestedAt: '2026-11-01T00:00:00Z',
  items: [],
  recovery: {
    situation: { code: 'NO_ACTION', name: '처리할 일 없음', description: '' },
    nextAutomaticAttemptAt: null,
    retryable: false,
    refundConfirmable: false,
    completable: false,
    lastDecision: null,
    version: 5,
  },
  returnApproval: null,
  returnDecision: decision(),
  ...overrides,
});

describe('returnActionPath', () => {
  it('같은 출처 /api/v1 의 승인 · 거절 경로다', () => {
    expect(returnActionPath('approve', '7')).toBe('/api/v1/admin/claims/7/return-approval');
    expect(returnActionPath('reject', '7')).toBe('/api/v1/admin/claims/7/return-rejection');
  });
});

describe('returnPanelMode', () => {
  it('승인 · 거절 가능한 접수 반품은 주문 관리 권한이 있거나 역할을 모르면 둘 다 보인다', () => {
    const both = { approve: true, reject: true, needsManager: false };
    expect(returnPanelMode(claim(), true)).toEqual(both);
    expect(returnPanelMode(claim(), null)).toEqual(both);
  });

  it('승인할 수 없어도 거절할 수 있으면 거절만 보인다', () => {
    const blocked = claim({
      returnDecision: decision({
        approvable: false,
        reason: fault('RETURN_IN_FLIGHT', '다른 반품 환불 진행 중'),
        refundByFault: [],
      }),
    });
    expect(returnPanelMode(blocked, true)).toEqual({
      approve: false,
      reject: true,
      needsManager: false,
    });
  });

  it('STAFF 는 버튼 대신 권한 안내다. 처리할 것이 없으면 안내도 없다', () => {
    expect(returnPanelMode(claim(), false)).toEqual({
      approve: false,
      reject: false,
      needsManager: true,
    });
    const closed = claim({
      returnDecision: decision({
        approvable: false,
        rejectable: false,
        reason: fault('NOT_DECIDABLE', '결정할 수 없음'),
      }),
    });
    expect(returnPanelMode(closed, false).needsManager).toBe(false);
  });

  it('취소 클레임 · 미리보기 없는 반품에는 아무것도 없다', () => {
    const none = { approve: false, reject: false, needsManager: false };
    expect(
      returnPanelMode(claim({ type: { code: 'CANCELLATION', name: '', description: '' } }), true),
    ).toEqual(none);
    expect(returnPanelMode(claim({ returnDecision: null }), true)).toEqual(none);
  });
});

describe('initialFault · faultOptions', () => {
  it('서버 기본 귀책을 고르고, 그 금액이 없으면 첫 귀책이다. 모르는 귀책은 뺀다', () => {
    expect(initialFault(decision())).toBe('SELLER');
    expect(
      initialFault(
        decision({
          refundByFault: [
            { fault: fault('CUSTOMER', '고객'), amount: { amount: 1, currency: 'KRW' } },
          ],
        }),
      ),
    ).toBe('CUSTOMER');
    expect(initialFault(decision({ refundByFault: [] }))).toBeNull();
    const unknown = decision({
      refundByFault: [
        { fault: fault('PLATFORM', '플랫폼'), amount: { amount: 1, currency: 'KRW' } },
      ],
    });
    expect(faultOptions(unknown)).toEqual([]);
  });
});

describe('deductionOf', () => {
  it('비면 0, 원화는 정수 · 쉼표 묶음만, 달러는 센트로 바꾼다', () => {
    expect(deductionOf('', 'KRW')).toEqual({ ok: true, money: { amount: 0, currency: 'KRW' } });
    expect(deductionOf('3,000', 'KRW')).toEqual({
      ok: true,
      money: { amount: 3000, currency: 'KRW' },
    });
    expect(deductionOf('12.5', 'USD')).toEqual({
      ok: true,
      money: { amount: 1250, currency: 'USD' },
    });
  });

  it('음수 · 소수(원화) · 숫자 아님은 막는다', () => {
    for (const text of ['-1', '10.5', 'abc', '1,00']) {
      expect(deductionOf(text, 'KRW')).toEqual({ ok: false, message: DEDUCTION_INVALID });
    }
    expect(deductionOf('1.234', 'USD').ok).toBe(false);
  });
});

describe('expectedRefundOf', () => {
  it('정책 환불액 − 차감액을 정책 통화로 낸다(서버가 대조하는 확인 값)', () => {
    expect(
      expectedRefundOf({ amount: 96000, currency: 'KRW' }, { amount: 3000, currency: 'KRW' }),
    ).toEqual({ amount: 93000, currency: 'KRW' });
  });
});

describe('approvalPlan', () => {
  const ready = { claim: claim(), fault: 'SELLER' as const, restock: true, deductionText: '3000' };

  it('모두 고르면 내역과 서버 계약 그대로의 본문을 낸다', () => {
    const plan = approvalPlan(ready);
    expect(plan.policy).toEqual({ amount: 96000, currency: 'KRW' });
    expect(plan.deduction).toEqual({ amount: 3000, currency: 'KRW' });
    expect(plan.refund).toEqual({ amount: 93000, currency: 'KRW' });
    expect(plan.blocker).toBeNull();
    expect(plan.request).toEqual({
      expectedVersion: 5,
      fault: 'SELLER',
      restock: true,
      deductionAmount: { amount: 3000, currency: 'KRW' },
      expectedRefundAmount: { amount: 93000, currency: 'KRW' },
    });
  });

  it('재고로 되돌리지 않음(false)도 고른 것이다', () => {
    expect(approvalPlan({ ...ready, restock: false }).request?.restock).toBe(false);
  });

  it('재고를 고르기 전에는 내역은 보이되 승인하지 않는다', () => {
    const plan = approvalPlan({ ...ready, restock: null });
    expect(plan.refund).toEqual({ amount: 93000, currency: 'KRW' });
    expect(plan.request).toBeNull();
    expect(plan.blocker).toEqual({
      field: 'restock',
      message: RESTOCK_REQUIRED,
      notPayable: false,
    });
  });

  it('차감액 형식이 틀리면 칸 오류이고 환불 예정액을 내지 않는다', () => {
    const plan = approvalPlan({ ...ready, deductionText: '1.5' });
    expect(plan.deductionError).toBe(DEDUCTION_INVALID);
    expect(plan.refund).toBeNull();
    expect(plan.blocker?.field).toBe('deduction');
  });

  it('환불 예정액이 0 이하면 승인하지 않는다(서버 ORDER_044 와 같은 기준)', () => {
    const zero = approvalPlan({ ...ready, deductionText: '96000' });
    expect(zero.refund).toEqual({ amount: 0, currency: 'KRW' });
    expect(zero.blocker).toEqual({
      field: 'deduction',
      message: REFUND_NOT_PAYABLE,
      notPayable: true,
    });
    expect(approvalPlan({ ...ready, deductionText: '100000' }).refund?.amount).toBe(-4000);
  });

  it('정책 환불액이 0 이하인 귀책은 승인하지 않는다', () => {
    const negative = claim({
      returnDecision: decision({
        refundByFault: [
          { fault: fault('CUSTOMER', '고객 귀책'), amount: { amount: -2000, currency: 'KRW' } },
          { fault: fault('SELLER', '판매자 귀책'), amount: { amount: 96000, currency: 'KRW' } },
        ],
      }),
    });
    const plan = approvalPlan({ ...ready, claim: negative, fault: 'CUSTOMER', deductionText: '0' });
    expect(plan.blocker).toEqual({ field: 'fault', message: POLICY_NOT_PAYABLE, notPayable: true });
  });

  it('귀책이 없거나 서버가 승인할 수 없다고 하면 막는다', () => {
    expect(approvalPlan({ ...ready, fault: null }).blocker?.message).toBe(FAULT_REQUIRED);
    const blocked = claim({
      returnDecision: decision({
        approvable: false,
        reason: { code: 'REFUND_NOT_OPEN', name: '자동 환불 꺼짐', description: '꺼져 있습니다' },
      }),
    });
    expect(approvalPlan({ ...ready, claim: blocked }).blocker).toEqual({
      field: null,
      message: '꺼져 있습니다',
      notPayable: false,
    });
  });

  it('달러 주문은 센트로 계산하고 통화를 정책 금액에 맞춘다', () => {
    const usd = claim({
      returnDecision: decision({
        refundByFault: [
          { fault: fault('SELLER', '판매자 귀책'), amount: { amount: 8950, currency: 'USD' } },
        ],
      }),
    });
    expect(approvalPlan({ ...ready, claim: usd, deductionText: '5.50' }).request).toMatchObject({
      deductionAmount: { amount: 550, currency: 'USD' },
      expectedRefundAmount: { amount: 8400, currency: 'USD' },
    });
  });
});

describe('approvalOutcome', () => {
  const after = (code: string) => claim({ status: { code, name: code, description: '' } });

  it('200 이어도 환불 · 복원 실패면 오류 문구다', () => {
    expect(approvalOutcome(after('COMPLETED')).tone).toBe('info');
    expect(approvalOutcome(after('REFUNDED')).tone).toBe('info');
    expect(approvalOutcome(after('APPROVED')).tone).toBe('info');
    expect(approvalOutcome(after('REFUND_FAILED')).tone).toBe('error');
    expect(approvalOutcome(after('RESTORATION_FAILED')).tone).toBe('error');
  });
});

describe('returnFailure', () => {
  it('401 은 입력을 지키는 폼 오류, 403 은 AccessNotice 다', () => {
    expect(returnFailure(apiError('unauthorized', 401, null), 'approve')).toEqual({
      kind: 'form',
      message: RETURN_SESSION_EXPIRED,
    });
    expect(returnFailure(apiError('forbidden', 403, null), 'reject')).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('버전 · 금액 불일치 · 결정 불가는 업무 문구로 바꾸고 상세를 다시 읽는다', () => {
    for (const code of ['ORDER_037', 'ORDER_046', 'ORDER_042']) {
      const failure = returnFailure(apiError('server', 409, code, '서버 원문'), 'approve');
      expect(failure).toMatchObject({ kind: 'inline', reload: true });
      if (failure.kind === 'inline') {
        expect(failure.message).not.toContain('ORDER_');
        expect(failure.message).not.toBe('서버 원문');
        expect(failure.message).not.toBe(RETURN_UNKNOWN);
      }
    }
    expect(returnFailure(apiError('unavailable', 503, 'ORDER_040'), 'approve')).toMatchObject({
      kind: 'inline',
      reload: true,
    });
  });

  it('본문 400 은 고칠 칸에 붙인다', () => {
    expect(returnFailure(apiError('server', 400, 'ORDER_171'), 'approve')).toEqual({
      kind: 'field',
      field: 'restock',
      message: RESTOCK_REQUIRED,
    });
    expect(returnFailure(apiError('server', 400, 'ORDER_172'), 'approve')).toMatchObject({
      field: 'deduction',
    });
    expect(returnFailure(apiError('server', 400, 'ORDER_173'), 'approve')).toMatchObject({
      field: 'deduction',
      message: REFUND_NOT_PAYABLE,
    });
    expect(returnFailure(apiError('server', 400, 'ORDER_170'), 'reject')).toMatchObject({
      field: 'reason',
      message: REASON_REQUIRED,
    });
    expect(returnFailure(apiError('server', 400, 'ORDER_148'), 'reject')).toMatchObject({
      message: REASON_TOO_LONG,
    });
    expect(returnFailure(apiError('server', 400, 'ORDER_151'), 'reject')).toMatchObject({
      message: REASON_INVALID_CHARACTER,
    });
  });

  it('결과 모름 · 연결 끊김은 승인 · 거절 모두 다시 읽어 실제 상태를 보인다', () => {
    expect(returnFailure(apiError('unknownOutcome', 504, null), 'reject')).toEqual({
      kind: 'inline',
      message: RETURN_UNKNOWN,
      reload: true,
    });
    expect(returnFailure(apiError('network', null, null), 'approve')).toMatchObject({
      message: RETURN_UNKNOWN,
    });
  });

  it('요청 제한은 다시 읽지 않고, 거절의 모르는 실패는 원문 대신 공통 실패 문구로 다시 읽는다', () => {
    expect(returnFailure(apiError('rateLimited', 429, 'GATEWAY_006'), 'approve')).toEqual({
      kind: 'inline',
      message: RETURN_RATE_LIMITED,
      reload: false,
    });
    expect(returnFailure(apiError('server', 409, 'ORDER_999', '원문'), 'reject')).toEqual({
      kind: 'inline',
      message: RETURN_FAILED,
      reload: true,
    });
    expect(returnFailure(new Error('boom'), 'reject')).toMatchObject({ message: RETURN_FAILED });
  });

  it('승인은 결과를 확신할 수 없는 실패를 "처리하지 못했다" 로 단정하지 않고 결과 모름으로 다시 읽는다', () => {
    for (const cause of [
      new Error('boom'),
      apiError('server', 500, 'COMMON_001', '서버 오류'),
      apiError('invalidResponse', 200, null),
      apiError('unavailable', 503, null),
      apiError('unavailable', 503, 'BFF_002'),
      apiError('server', 409, 'ORDER_999', '원문'),
    ]) {
      expect(returnFailure(cause, 'approve')).toEqual({
        kind: 'inline',
        message: RETURN_UNKNOWN,
        reload: true,
      });
    }
  });
});

describe('failureTransition', () => {
  it('쓰기 401 은 확인 단계 · 스냅숏을 그대로 두고 확인 단계 전체 오류로 보인다', () => {
    expect(failureTransition({ kind: 'form', message: '만료' }, 'approve')).toEqual({
      closeConfirm: false,
      keepSnapshot: true,
      fieldErrors: {},
      formError: '만료',
      notice: null,
      denied: null,
      reload: false,
      focus: 'return-form-error',
    });
  });

  it('승인 칸 오류는 확인 단계를 닫고 그 칸에 붙인다 — 고친 뒤 새 금액으로 다시 확인한다', () => {
    expect(
      failureTransition({ kind: 'field', field: 'deduction', message: '차감액' }, 'approve'),
    ).toMatchObject({
      closeConfirm: true,
      keepSnapshot: false,
      fieldErrors: { deduction: '차감액' },
      reload: false,
      focus: 'return-deduction',
    });
  });

  it('사유 칸 오류는 거절 확인 단계를 열어 둔 채 사유 칸에 붙인다', () => {
    expect(
      failureTransition({ kind: 'field', field: 'reason', message: '사유' }, 'reject'),
    ).toMatchObject({
      closeConfirm: false,
      keepSnapshot: true,
      fieldErrors: { reason: '사유' },
      focus: 'return-reason',
    });
  });

  it('결과 문구는 확인 단계를 닫고 reload 를 그대로 넘긴다 · 403 은 AccessNotice 로 누른 버튼에 포커스한다', () => {
    expect(failureTransition({ kind: 'inline', message: '바뀜', reload: true }, 'approve')).toEqual(
      {
        closeConfirm: true,
        keepSnapshot: false,
        fieldErrors: {},
        formError: null,
        notice: { tone: 'error', message: '바뀜' },
        denied: null,
        reload: true,
        focus: 'return-notice',
      },
    );
    expect(
      failureTransition({ kind: 'inline', message: '제한', reload: false }, 'reject').reload,
    ).toBe(false);
    expect(failureTransition({ kind: 'access', denied: 'forbidden' }, 'reject')).toMatchObject({
      closeConfirm: true,
      denied: 'forbidden',
      notice: null,
      focus: 'return-reject',
    });
  });
});

describe('confirmStillValid', () => {
  const both = { approve: true, reject: true, needsManager: false };
  const request = {
    expectedVersion: 5,
    fault: 'SELLER' as const,
    restock: true,
    deductionAmount: { amount: 3000, currency: 'KRW' as const },
    expectedRefundAmount: { amount: 93000, currency: 'KRW' as const },
  };

  it('버전 · 금액이 그대로면 승인 확인 단계를 둔다', () => {
    expect(confirmStillValid(claim(), both, { action: 'approve', request })).toBe(true);
  });

  it('다시 읽은 상세의 버전이나 정책 환불액이 바뀌면 닫는다', () => {
    const bumped = claim({ recovery: { ...claim().recovery, version: 6 } });
    expect(confirmStillValid(bumped, both, { action: 'approve', request })).toBe(false);
    const repriced = claim({
      returnDecision: decision({
        refundByFault: [
          { fault: fault('SELLER', '판매자 귀책'), amount: { amount: 95000, currency: 'KRW' } },
        ],
      }),
    });
    expect(confirmStillValid(repriced, both, { action: 'approve', request })).toBe(false);
  });

  it('버튼이 사라지면 닫는다 · 거절은 저장한 버전과 같을 때만 둔다', () => {
    const none = { approve: false, reject: false, needsManager: false };
    expect(confirmStillValid(claim(), none, { action: 'approve', request })).toBe(false);
    expect(confirmStillValid(claim(), both, { action: 'reject', expectedVersion: 5 })).toBe(true);
    expect(confirmStillValid(claim(), both, { action: 'reject', expectedVersion: 4 })).toBe(false);
    expect(confirmStillValid(claim(), none, { action: 'reject', expectedVersion: 5 })).toBe(false);
  });
});

describe('deductionUnitLabel', () => {
  it('통화 코드 대신 운영자 말로 단위를 쓴다', () => {
    expect(deductionUnitLabel('KRW')).toBe('원');
    expect(deductionUnitLabel('USD')).toBe('달러, 센트까지');
  });
});
