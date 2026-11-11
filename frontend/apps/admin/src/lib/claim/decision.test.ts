import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { canManageSettlement } from '@/lib/auth/permissions';
import {
  DECISION_FAILED,
  DECISION_REASON_MAX,
  DECISION_SESSION_EXPIRED,
  DECISION_UNKNOWN,
  REASON_INVALID_CHARACTER,
  REASON_REQUIRED,
  REASON_TOO_LONG,
  decisionActions,
  decisionFailure,
  decisionNeedsOwner,
  decisionPath,
  decisionReasonError,
  normalizeDecisionReason,
} from './decision';
import type { ClaimDetail } from './types';

const apiError = (
  kind: ConstructorParameters<typeof ApiError>[0],
  status: number | null,
  code: string | null,
  message: string | null = null,
) => new ApiError(kind, status, code, message, []);

const claim = (
  flags: { refundConfirmable?: boolean; completable?: boolean } = {},
  type = 'CANCELLATION',
) =>
  ({
    claimId: '7',
    orderId: '2',
    type: { code: type, name: type, description: '' },
    status: { code: 'REFUND_FAILED', name: '환불 실패', description: '' },
    expectedRefund: null,
    requestedAt: '2026-11-01T00:00:00Z',
    items: [],
    recovery: {
      situation: { code: 'REFUND_REJECTED', name: '환불 거절', description: '' },
      nextAutomaticAttemptAt: null,
      retryable: false,
      refundConfirmable: flags.refundConfirmable ?? false,
      completable: flags.completable ?? false,
      lastDecision: null,
      version: 3,
    },
  }) satisfies ClaimDetail;

describe('decisionPath', () => {
  it('같은 출처 /api/v1 의 확정 · 종결 경로다(BFF 범용 프록시가 넘긴다)', () => {
    expect(decisionPath('refundConfirmation', '7')).toBe(
      '/api/v1/admin/claims/7/refund-confirmation',
    );
    expect(decisionPath('completion', '7')).toBe('/api/v1/admin/claims/7/completion');
  });
});

describe('canManageSettlement', () => {
  it('대표(ADMIN · OWNER)만 true, MANAGER · STAFF 는 false, 역할을 모르면 null', () => {
    expect(canManageSettlement('ADMIN')).toBe(true);
    expect(canManageSettlement('OWNER')).toBe(true);
    expect(canManageSettlement('MANAGER')).toBe(false);
    expect(canManageSettlement('STAFF')).toBe(false);
    expect(canManageSettlement(null)).toBeNull();
  });
});

describe('decisionActions · decisionNeedsOwner', () => {
  it('서버 플래그가 true 인 결정만 보인다', () => {
    expect(decisionActions(claim(), true)).toEqual([]);
    expect(decisionActions(claim({ refundConfirmable: true }), true)).toEqual([
      'refundConfirmation',
    ]);
    expect(decisionActions(claim({ completable: true }), true)).toEqual(['completion']);
  });

  it('대표가 아니면(false) 숨기고 안내를 두며, 역할을 모르면(null) 숨기지 않는다', () => {
    const confirmable = claim({ refundConfirmable: true });
    expect(decisionActions(confirmable, false)).toEqual([]);
    expect(decisionNeedsOwner(confirmable, false)).toBe(true);
    expect(decisionActions(confirmable, null)).toEqual(['refundConfirmation']);
    expect(decisionNeedsOwner(confirmable, null)).toBe(false);
    // 플래그가 없으면 권한 안내도 없다
    expect(decisionNeedsOwner(claim(), false)).toBe(false);
  });

  it('반품 접수에는 플래그가 와도 보이지 않는다', () => {
    const ret = claim({ refundConfirmable: true, completable: true }, 'RETURN');
    expect(decisionActions(ret, true)).toEqual([]);
    expect(decisionNeedsOwner(ret, false)).toBe(false);
  });
});

describe('decisionReasonError · normalizeDecisionReason', () => {
  it('비었거나 공백(전각 공백 · 줄 구분자 포함)만이면 필수 문구다', () => {
    expect(decisionReasonError('')).toBe(REASON_REQUIRED);
    expect(decisionReasonError('   ')).toBe(REASON_REQUIRED);
    expect(decisionReasonError('　 ')).toBe(REASON_REQUIRED);
  });

  it('앞뒤 공백을 빼고 보낸다', () => {
    expect(normalizeDecisionReason('  결제 관리 화면에서 취소 완료 확인 ')).toBe(
      '결제 관리 화면에서 취소 완료 확인',
    );
    expect(decisionReasonError('  확인함 ')).toBeNull();
  });

  it('200자를 넘으면 길이 문구다(앞뒤 공백은 세지 않는다)', () => {
    expect(decisionReasonError('가'.repeat(DECISION_REASON_MAX))).toBeNull();
    expect(decisionReasonError(` ${'가'.repeat(DECISION_REASON_MAX)} `)).toBeNull();
    expect(decisionReasonError('가'.repeat(DECISION_REASON_MAX + 1))).toBe(REASON_TOO_LONG);
  });

  it('가운데 제어 · 서식 문자 · 줄 구분자는 막는다', () => {
    expect(decisionReasonError('확인\t함')).toBe(REASON_INVALID_CHARACTER);
    expect(decisionReasonError('확인​함')).toBe(REASON_INVALID_CHARACTER);
    expect(decisionReasonError('확인 함')).toBe(REASON_INVALID_CHARACTER);
  });
});

describe('decisionFailure', () => {
  it('403 은 공통 403 안내, 쓰기 401 은 입력을 지키는 확인 단계 전체 문구다', () => {
    expect(decisionFailure(apiError('forbidden', 403, 'SECURITY_005'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(decisionFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'form',
      message: DECISION_SESSION_EXPIRED,
    });
  });

  it.each(['ORDER_170', 'ORDER_148', 'ORDER_151'])(
    '사유 검증 %s 는 서버 문구를 사유 칸에 두고 다시 읽지 않는다',
    (code) => {
      expect(decisionFailure(apiError('validation', 400, code, '사유 문구'))).toEqual({
        kind: 'reason',
        message: '사유 문구',
      });
    },
  );

  it.each([
    ['ORDER_037', 409, 'conflict'],
    ['ORDER_041', 409, 'conflict'],
    ['ORDER_169', 400, 'validation'],
    ['ORDER_032', 404, 'notFound'],
    ['ORDER_040', 503, 'unavailable'],
  ] as const)('%s 는 서버 문구를 두고 상세를 다시 읽는다', (code, status, kind) => {
    expect(decisionFailure(apiError(kind, status, code, '서버 문구'))).toEqual({
      kind: 'inline',
      message: '서버 문구',
      reload: true,
    });
  });

  it('결과 모름 · 연결 끊김 · 알 수 없는 실패는 다시 읽어 실제 상태를 보인다', () => {
    expect(decisionFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'inline',
      message: DECISION_UNKNOWN,
      reload: true,
    });
    expect(decisionFailure(apiError('network', null, null))).toEqual({
      kind: 'inline',
      message: DECISION_UNKNOWN,
      reload: true,
    });
    expect(decisionFailure(new Error('boom'))).toEqual({
      kind: 'inline',
      message: DECISION_FAILED,
      reload: true,
    });
    expect(decisionFailure(apiError('server', 500, 'COMMON_500', '서버'))).toEqual({
      kind: 'inline',
      message: DECISION_FAILED,
      reload: true,
    });
  });

  it('그 밖의 4xx 코드는 서버 문구를 두고 다시 읽는다', () => {
    expect(decisionFailure(apiError('validation', 400, 'ORDER_166', '아이디'))).toEqual({
      kind: 'inline',
      message: '아이디',
      reload: true,
    });
  });
});
