import { describe, expect, it, vi } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  claimLineViews,
  claimReasonLabel,
  claimStatusGroup,
  claimStatusLabel,
  claimTypeLabel,
  orderClaimsPath,
  parseOrderClaims,
  readOrderClaims,
} from './claims';

const meta = (code: string, name = code) => ({ code, name, description: `${name} 설명` });
const rawClaim = (patch: Record<string, unknown> = {}) => ({
  claimId: '4611686018427387907',
  type: meta('RETURN', '반품'),
  status: meta('REQUESTED', '접수'),
  expectedRefund: null,
  requestedAt: '2026-11-10T03:00:00Z',
  items: [{ orderItemId: '11', quantity: 2, reason: meta('DEFECTIVE', '상품 불량') }],
  ...patch,
});
const envelope = (dataBody: unknown, status = 200, success = true) =>
  new Response(
    JSON.stringify({
      dataHeader: {
        success,
        resultCode: success ? null : 'ORDER_001',
        resultMessage: null,
        fieldErrors: null,
      },
      dataBody,
    }),
    { status, headers: { 'content-type': 'application/json' } },
  );

describe('orderClaimsPath', () => {
  it('주문 상세 아래 claims 다', () => {
    expect(orderClaimsPath('42')).toBe('/api/v1/orders/42/claims');
  });
});

describe('parseOrderClaims', () => {
  it('서버 순서(최신순)를 그대로 읽고 환불 예정액 null 은 null 로 둔다', () => {
    const claims = parseOrderClaims([
      rawClaim({
        claimId: '2',
        type: meta('CANCELLATION', '취소'),
        expectedRefund: { amount: 39000, currency: 'KRW' },
      }),
      rawClaim({ claimId: '1' }),
    ]);
    expect(claims?.map((claim) => claim.claimId)).toEqual(['2', '1']);
    expect(claims?.[0]?.expectedRefund).toEqual({ amount: 39000, currency: 'KRW' });
    expect(claims?.[1]?.expectedRefund).toBeNull();
    expect(claims?.[1]?.items).toEqual([
      { orderItemId: '11', quantity: 2, reason: meta('DEFECTIVE', '상품 불량') },
    ]);
  });

  it('빈 배열은 빈 목록이다', () => {
    expect(parseOrderClaims([])).toEqual([]);
  });

  it('배열이 아니면 null', () => {
    expect(parseOrderClaims(null)).toBeNull();
    expect(parseOrderClaims({ content: [] })).toBeNull();
  });

  it('모양이 어긋난 클레임이 하나라도 있으면 null — 한 건을 조용히 빼지 않는다', () => {
    for (const patch of [
      { claimId: 4611686018427387907 },
      { type: null },
      { status: { name: '접수' } },
      { requestedAt: 'yesterday' },
      { expectedRefund: { amount: '39000', currency: 'KRW' } },
      { items: null },
      { items: [{ orderItemId: 11, quantity: 1, reason: meta('OTHER') }] },
      { items: [{ orderItemId: '11', quantity: 0, reason: meta('OTHER') }] },
      { items: [{ orderItemId: '11', quantity: 1 }] },
    ]) {
      expect(parseOrderClaims([rawClaim(), rawClaim(patch)])).toBeNull();
    }
  });
});

describe('readOrderClaims', () => {
  it('성공은 ready, 실패 · 모양이 어긋난 응답은 failed', async () => {
    const ok = vi.fn().mockResolvedValue(envelope([rawClaim()]));
    const ready = await readOrderClaims('42', ok as unknown as typeof fetch);
    expect(ready.status).toBe('ready');
    expect(ok.mock.calls[0]?.[0]).toBe('/api/v1/orders/42/claims');

    const broken = vi.fn().mockResolvedValue(envelope({ claims: [] }));
    expect(await readOrderClaims('42', broken as unknown as typeof fetch)).toEqual({
      status: 'failed',
      retrying: false,
    });

    const notFound = vi.fn().mockResolvedValue(envelope(null, 404, false));
    expect((await readOrderClaims('42', notFound as unknown as typeof fetch)).status).toBe(
      'failed',
    );
    const offline = vi.fn().mockRejectedValue(new TypeError('offline'));
    expect((await readOrderClaims('42', offline as unknown as typeof fetch)).status).toBe('failed');
  });
});

describe('claimStatusLabel', () => {
  it('운영자 처리 대기(환불 · 재고 복원 실패)와 모르는 상태는 처리 중이다', () => {
    expect(claimStatusGroup(meta('REQUESTED'))).toBe('requested');
    expect(claimStatusGroup(meta('REFUNDED'))).toBe('refunded');
    expect(claimStatusGroup(meta('COMPLETED'))).toBe('completed');
    expect(claimStatusGroup(meta('REFUND_FAILED'))).toBe('processing');
    expect(claimStatusGroup(meta('RESTORATION_FAILED'))).toBe('processing');
    expect(claimStatusGroup(meta('SOMETHING_NEW'))).toBe('processing');
  });

  it('두 언어 모두 사전 문구다(서버의 운영자용 이름을 보이지 않는다)', () => {
    expect(claimStatusLabel(meta('REFUND_FAILED', '환불 실패'), ko.orders)).toBe('처리 중');
    expect(claimStatusLabel(meta('COMPLETED', '완료'), ko.orders)).toBe('처리 완료');
    expect(claimStatusLabel(meta('REQUESTED', '접수'), en.orders)).toBe('Requested');
  });
});

describe('claimTypeLabel · claimReasonLabel', () => {
  it('국문은 서버 이름, 영문은 사전(없으면 서버 이름)', () => {
    const type = meta('CANCELLATION', '취소');
    expect(claimTypeLabel(type, ko.orders, 'ko')).toBe('취소');
    expect(claimTypeLabel(type, en.orders, 'en')).toBe('Cancellation');
    expect(claimTypeLabel(meta('EXCHANGE', '교환'), en.orders, 'en')).toBe('교환');
  });

  it('사유는 취소 · 반품 사유 사전을 함께 쓴다', () => {
    expect(claimReasonLabel(meta('DEFECTIVE', '상품 불량'), ko.orders, 'ko')).toBe('상품 불량');
    expect(claimReasonLabel(meta('DEFECTIVE', '상품 불량'), en.orders, 'en')).toBe(
      en.orders.returnReasons.DEFECTIVE,
    );
    expect(claimReasonLabel(meta('PAYMENT_ISSUE', '결제 문제'), en.orders, 'en')).toBe(
      en.orders.cancelReasons.PAYMENT_ISSUE,
    );
    expect(claimReasonLabel(meta('NEW_REASON', '새 사유'), en.orders, 'en')).toBe('새 사유');
  });
});

describe('claimLineViews', () => {
  const order = {
    items: [
      { orderItemId: '11', productName: '모시 저고리', optionName: '네이비 / M' },
      { orderItemId: '12', productName: '생활한복 바지', optionName: '' },
    ],
  } as Parameters<typeof claimLineViews>[1];

  it('주문 항목 아이디로 상품명 · 옵션을 잇고, 못 찾으면 이름은 null', () => {
    const views = claimLineViews(
      {
        items: [
          { orderItemId: '12', quantity: 1, reason: meta('OTHER') },
          { orderItemId: '99', quantity: 3, reason: meta('OTHER') },
        ],
      },
      order,
    );
    expect(views).toEqual([
      {
        orderItemId: '12',
        productName: '생활한복 바지',
        optionName: '',
        quantity: 1,
        reason: meta('OTHER'),
      },
      { orderItemId: '99', productName: null, optionName: '', quantity: 3, reason: meta('OTHER') },
    ]);
  });
});
