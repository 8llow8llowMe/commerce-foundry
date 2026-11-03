import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DEFAULT_QUERY } from '@/lib/payment/query';
import type { PaymentListItem } from '@/lib/payment/types';
import { helpTopicFor } from '@/lib/tour/tours';
import { PaymentFilters } from './PaymentFilters';
import { orderSyncLabel, PaymentTable } from './PaymentTable';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const payment = (overrides: Partial<PaymentListItem> = {}): PaymentListItem => ({
  paymentId: '4611686018427387910',
  orderId: '4611686018427387904',
  status: { code: 'UNKNOWN', name: '결제 확인 중', description: '결제 확인 중 설명' },
  amount: { amount: 93000, currency: 'KRW' },
  method: null,
  unknownReason: { code: 'TIMEOUT', name: '승인 응답 시간 초과', description: '시간 초과 설명' },
  orderSynced: false,
  orderSyncFailureCode: null,
  createdAt: '2026-11-01T01:00:00Z',
  updatedAt: '2026-11-01T01:00:05Z',
  approvalStartedAt: '2026-11-01T01:00:01Z',
  approvedAt: null,
  ...overrides,
});

describe('PaymentTable', () => {
  it('주문 상세 링크 · 서버 상태 name · 미확정 사유 · formatMoney 원금 · 회신 상태를 보인다', () => {
    const html = renderToStaticMarkup(<PaymentTable payments={[payment()]} />);
    expect(html).toContain('href="/orders/4611686018427387904"');
    expect(html).toContain('4611686018427387910');
    expect(html).toContain('>결제 확인 중<');
    expect(html).toContain('title="결제 확인 중 설명"');
    expect(html).toContain('사유: 승인 응답 시간 초과');
    expect(html).toContain('93,000원');
    expect(html).toContain('>미수신<');
    // 승인 전이라 수단 · 완료 시각이 없다
    expect(html).toContain('—');
  });

  it('수단 · USD 금액을 서버 값 그대로 보이고 사유가 없으면 그 줄을 뺀다', () => {
    const html = renderToStaticMarkup(
      <PaymentTable
        payments={[
          payment({
            status: { code: 'DONE', name: '결제 완료', description: '' },
            method: { code: 'CARD', name: '카드', description: '카드 설명' },
            amount: { amount: 8950, currency: 'USD' },
            unknownReason: null,
            orderSynced: true,
            approvedAt: '2026-11-01T01:00:03Z',
          }),
        ]}
      />,
    );
    expect(html).toContain('>카드<');
    expect(html).toContain('$89.50');
    expect(html).not.toContain('사유:');
    expect(html).toContain('>수신<');
  });
});

describe('orderSyncLabel', () => {
  it('수신 · 거절(코드) · 미수신을 가른다', () => {
    expect(orderSyncLabel(payment({ orderSynced: true }))).toBe('수신');
    expect(orderSyncLabel(payment({ orderSyncFailureCode: 'ORDER_020' }))).toBe('거절(ORDER_020)');
    expect(orderSyncLabel(payment())).toBe('미수신');
  });
});

describe('PaymentFilters', () => {
  it('REFUND_FAILED 없이 상태를 고르고, 지금 조건의 바로가기를 표시한다', () => {
    const html = renderToStaticMarkup(
      <PaymentFilters query={{ ...DEFAULT_QUERY, status: 'UNKNOWN' }} />,
    );
    expect(html).toContain('value="UNKNOWN"');
    expect(html).not.toContain('REFUND_FAILED');
    expect(html).toMatch(/aria-current="page"[^>]*href="\/payments\?status=UNKNOWN"/);
    expect(html.match(/aria-current/g)).toHaveLength(1);
    expect(html).toContain('href="/payments?status=DONE&amp;orderSynced=false"');
    expect(html).toContain('조건 초기화');
  });

  it('조건이 없으면 초기화를 보이지 않는다', () => {
    const html = renderToStaticMarkup(<PaymentFilters query={DEFAULT_QUERY} />);
    expect(html).not.toContain('조건 초기화');
    expect(html).not.toContain('aria-current');
  });
});

describe('결제 확인 투어 앵커(#228 payments-v1)', () => {
  it('조건 · 표가 투어가 가리키는 앵커를 단다', () => {
    const targets = helpTopicFor('/payments').tour?.steps.flatMap((step) => step.target ?? []);
    expect(targets).toEqual(['payment-filters', 'payment-table']);
    expect(renderToStaticMarkup(<PaymentFilters query={DEFAULT_QUERY} />)).toContain(
      'data-tour="payment-filters"',
    );
    expect(renderToStaticMarkup(<PaymentTable payments={[payment()]} />)).toContain(
      'data-tour="payment-table"',
    );
  });
});
