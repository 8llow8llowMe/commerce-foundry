import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { LOAD_FAILED, SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  bulkFailure,
  bulkSummary,
  normalizeReason,
  retryableOrderIds,
  runBulkChange,
  runStatusChange,
  STATUS_STALE_NOTICE,
  STATUS_UNKNOWN_NOTICE,
  statusChangeFailure,
  stillApplicable,
  type Call,
} from './status-change';
import { bulkResponse, orderDetail } from './test-fixtures';
import type { OrderDetail } from './types';

const conflict = (code: string, message: string) => new ApiError('conflict', 409, code, message);

describe('normalizeReason', () => {
  it('앞뒤 공백을 빼고 비면 null 이다', () => {
    expect(normalizeReason('  포장 시작 ')).toEqual({ ok: true, reason: '포장 시작' });
    expect(normalizeReason('   ')).toEqual({ ok: true, reason: null });
  });

  it('200자를 넘으면 받지 않는다', () => {
    expect(normalizeReason('가'.repeat(200)).ok).toBe(true);
    expect(normalizeReason('가'.repeat(201)).ok).toBe(false);
  });
});

describe('stillApplicable', () => {
  const shown = orderDetail();

  it('같은 상태이고 목표가 지금의 nextStatuses 에 있으면 보낸다', () => {
    expect(stillApplicable(shown, orderDetail(), 'PREPARING')).toBe(true);
  });

  it('그사이 상태가 바뀌었으면 — 목표가 여전히 허용돼도 — 보내지 않는다', () => {
    const making = orderDetail({
      status: { code: 'MAKING', name: '제작 중', description: '' },
      nextStatuses: [{ code: 'PREPARING', name: '상품 준비중', description: '' }],
    });
    expect(stillApplicable(shown, making, 'PREPARING')).toBe(false);
  });

  it('목표가 지금 허용되지 않으면 보내지 않는다', () => {
    expect(stillApplicable(shown, orderDetail({ nextStatuses: [] }), 'PREPARING')).toBe(false);
  });
});

describe('statusChangeFailure', () => {
  it('401 은 폼을 지키는 재로그인 안내, 403 은 접근 안내다', () => {
    expect(statusChangeFailure(new ApiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(statusChangeFailure(new ApiError('forbidden', 403, 'GATEWAY_002', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('거절 전이(ORDER_020 · 023 · 024)는 최신 상세를 다시 읽고 서버 문구를 보인다', () => {
    for (const code of ['ORDER_020', 'ORDER_023', 'ORDER_024']) {
      expect(statusChangeFailure(conflict(code, `${code} 문구`))).toEqual({
        kind: 'stale',
        message: `${code} 문구`,
      });
    }
  });

  it('경합(ORDER_025)은 "새로고침하라" 는 서버 문구 대신 다시 읽었다는 화면 문구다', () => {
    expect(statusChangeFailure(conflict('ORDER_025', '새로고침한 뒤 다시 시도해 주세요.'))).toEqual(
      { kind: 'stale', message: STATUS_STALE_NOTICE },
    );
  });

  it('결과를 모르는 실패는 다시 읽기다(자동 재시도 없음)', () => {
    expect(statusChangeFailure(new ApiError('unknownOutcome', 504, null, null)).kind).toBe(
      'unknownOutcome',
    );
    expect(statusChangeFailure(new ApiError('network', null, null, null)).kind).toBe(
      'unknownOutcome',
    );
  });

  it('없는 주문은 notFound 다', () => {
    expect(statusChangeFailure(new ApiError('notFound', 404, 'ORDER_001', null))).toEqual({
      kind: 'notFound',
    });
  });

  it('배송 완료 사유 없음(ORDER_027) · 사유 검증 오류는 사유 칸에 붙인다', () => {
    expect(
      statusChangeFailure(new ApiError('server', 400, 'ORDER_027', '근거를 남겨 주세요.')),
    ).toEqual({ kind: 'errors', errors: { reason: ['근거를 남겨 주세요.'] } });
    expect(
      statusChangeFailure(
        new ApiError('validation', 400, 'ORDER_148', null, [
          { code: 'ORDER_148', field: 'reason', message: '사유는 200자 이하' },
          { code: 'ORDER_147', field: 'status', message: '상태를 골라 주세요' },
        ]),
      ),
    ).toEqual({
      kind: 'errors',
      errors: { reason: ['사유는 200자 이하'], form: ['상태를 골라 주세요'] },
    });
  });

  it('그 밖의 서버 오류는 일반 문구다', () => {
    expect(statusChangeFailure(new ApiError('server', 500, 'ORDER_026', '내부'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(statusChangeFailure(new Error('x'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('bulkFailure', () => {
  it('요청 전체가 거절된 400 은 서버 문구를 그대로 보인다', () => {
    expect(
      bulkFailure(new ApiError('server', 400, 'ORDER_153', '배송 완료는 한 건씩 바꿔 주세요.')),
    ).toEqual({ kind: 'errors', errors: { form: ['배송 완료는 한 건씩 바꿔 주세요.'] } });
  });

  it('결과 모름 · 401 · 403', () => {
    expect(bulkFailure(new ApiError('unknownOutcome', 502, null, null)).kind).toBe(
      'unknownOutcome',
    );
    expect(bulkFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(bulkFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });
});

describe('retryableOrderIds · bulkSummary', () => {
  it('다시 보내면 달라질 수 있는 실패(ORDER_025 · 026)만 요청 순서로 고른다', () => {
    expect(retryableOrderIds(bulkResponse())).toEqual([]);
    const response = bulkResponse({
      results: [
        { orderId: '21', result: 'FAILED', errorCode: 'ORDER_026', message: '저장 오류' },
        { orderId: '22', result: 'FAILED', errorCode: 'ORDER_020', message: '취소 완료' },
        { orderId: '23', result: 'FAILED', errorCode: 'ORDER_025', message: '경합' },
        { orderId: '24', result: 'CHANGED', errorCode: null, message: null },
      ],
    });
    expect(retryableOrderIds(response)).toEqual(['21', '23']);
  });

  it('모두 실패하면 "일부" 라고 하지 않는다', () => {
    expect(bulkSummary(bulkResponse({ changedCount: 0, unchangedCount: 0, failedCount: 2 }))).toBe(
      '바꾸지 못했습니다 — 바꿈 0건 · 실패 2건',
    );
  });

  it('일부 실패를 성공처럼 말하지 않는다', () => {
    expect(bulkSummary(bulkResponse())).toBe(
      '일부 주문을 바꾸지 못했습니다 — 바꿈 1건 · 이미 그 상태 1건 · 실패 1건',
    );
    expect(bulkSummary(bulkResponse({ changedCount: 2, unchangedCount: 0, failedCount: 0 }))).toBe(
      '모두 처리했습니다 — 바꿈 2건 · 실패 0건',
    );
  });
});

describe('runStatusChange', () => {
  const shown = orderDetail();
  const changed = orderDetail({
    status: { code: 'PREPARING', name: '상품 준비중', description: '' },
    nextStatuses: [],
  });

  /** 경로별 응답 — 함수면 던진다 */
  const fakeCall = (detail: OrderDetail | Error, patch?: OrderDetail | Error) =>
    vi.fn(async (_path: string, init?: { method?: string }) => {
      const result = init?.method === 'PATCH' ? patch : detail;
      if (result instanceof Error) throw result;
      return result;
    }) as unknown as Call & ReturnType<typeof vi.fn>;
  const patches = (call: ReturnType<typeof vi.fn>) =>
    call.mock.calls.filter(
      ([, init]) => (init as { method?: string } | undefined)?.method === 'PATCH',
    );

  it('다시 읽은 상태가 그대로면 PATCH 를 한 번 보내고 바뀐 상세를 돌려준다', async () => {
    const call = fakeCall(orderDetail(), changed);
    expect(await runStatusChange(call, shown, 'PREPARING', '포장 시작')).toEqual({
      kind: 'changed',
      order: changed,
    });
    expect(call.mock.calls[0]?.[0]).toBe('/api/v1/admin/orders/4611686018427387904');
    expect(patches(call)).toEqual([
      [
        '/api/v1/admin/orders/4611686018427387904/status',
        { method: 'PATCH', json: { status: 'PREPARING', reason: '포장 시작' } },
      ],
    ]);
  });

  it('그사이 상태가 바뀌었으면(고객 취소 등) PATCH 를 보내지 않고 최신 상세를 돌려준다', async () => {
    const canceled = orderDetail({
      status: { code: 'CANCEL_REQUESTED', name: '취소 요청', description: '' },
      nextStatuses: [],
    });
    const call = fakeCall(canceled, changed);
    expect(await runStatusChange(call, shown, 'PREPARING', null)).toEqual({
      kind: 'stale',
      notice: STATUS_STALE_NOTICE,
      fresh: canceled,
    });
    expect(patches(call)).toHaveLength(0);
  });

  it('다시 읽기가 502 면 보내지 않았으므로 "결과 모름" 이 아니라 불러오기 실패다', async () => {
    const call = fakeCall(new ApiError('unknownOutcome', 502, null, null));
    expect(await runStatusChange(call, shown, 'PREPARING', null)).toEqual({
      kind: 'errors',
      errors: { form: [LOAD_FAILED] },
    });
    expect(patches(call)).toHaveLength(0);
  });

  it('PATCH 경합 409 · 결과 모름 502 는 다시 보내지 않고 다시 읽기로 넘긴다', async () => {
    const raced = fakeCall(orderDetail(), conflict('ORDER_025', '새로고침'));
    expect(await runStatusChange(raced, shown, 'PREPARING', null)).toEqual({
      kind: 'stale',
      notice: STATUS_STALE_NOTICE,
      fresh: null,
    });
    expect(patches(raced)).toHaveLength(1);

    const unknown = fakeCall(orderDetail(), new ApiError('unknownOutcome', 504, null, null));
    expect(await runStatusChange(unknown, shown, 'PREPARING', null)).toEqual({
      kind: 'stale',
      notice: STATUS_UNKNOWN_NOTICE,
      fresh: null,
    });
    expect(patches(unknown)).toHaveLength(1);
  });

  it('배송 완료 사유 없음은 사유 칸 오류다', async () => {
    const shipping = orderDetail({
      status: { code: 'SHIPPING', name: '배송 중', description: '' },
      nextStatuses: [{ code: 'DELIVERED', name: '배송 완료', description: '' }],
    });
    const call = fakeCall(
      shipping,
      new ApiError('server', 400, 'ORDER_027', '근거를 남겨 주세요.'),
    );
    expect(await runStatusChange(call, shipping, 'DELIVERED', null)).toEqual({
      kind: 'errors',
      errors: { reason: ['근거를 남겨 주세요.'] },
    });
  });
});

describe('runBulkChange', () => {
  it('요청 본문 그대로 한 번 보내고 행별 결과를 돌려준다', async () => {
    const response = bulkResponse();
    const call = vi.fn(async () => response) as unknown as Call & ReturnType<typeof vi.fn>;
    const request = { orderIds: ['11', '12', '13'], status: 'PREPARING' as const, reason: null };
    expect(await runBulkChange(call, request)).toEqual({ kind: 'done', response });
    expect(call.mock.calls).toEqual([
      ['/api/v1/admin/orders/status-changes', { method: 'POST', json: request }],
    ]);
  });

  it('결과 모름은 다시 보내지 않는다', async () => {
    const call = vi.fn(async () => {
      throw new ApiError('unknownOutcome', 504, null, null);
    }) as unknown as Call & ReturnType<typeof vi.fn>;
    expect(await runBulkChange(call, { orderIds: ['11'], status: 'MAKING', reason: null })).toEqual(
      { kind: 'unknownOutcome' },
    );
    expect(call).toHaveBeenCalledTimes(1);
  });
});
