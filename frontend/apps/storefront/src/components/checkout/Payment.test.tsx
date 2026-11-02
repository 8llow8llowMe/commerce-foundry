import { formatMoney } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { PaymentPrepared, PaymentResponse } from '@/lib/checkout/payment';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { PaymentFailed } from './PaymentFailed';
import { PaymentResult, PaymentResultView, type ResultView } from './PaymentResult';
import { NotPayable, PaymentSummary, PaymentView } from './PaymentView';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const t = ko.payment;
const payment: PaymentResponse = {
  paymentId: '501',
  orderId: '42',
  pgOrderId: '501',
  status: { code: 'DONE', name: '결제 완료', description: '' },
  amount: { amount: 39000, currency: 'KRW' },
  orderName: '저고리 외 1건',
  method: { code: 'EASY_PAY', name: '간편결제', description: '' },
  easyPayProvider: '카카오페이',
  approvedAt: '2026-11-02T01:00:00Z',
  failure: null,
};
const won = (money: PaymentResponse['amount']) => formatMoney(money, 'ko');
const view = (v: Exclude<ResultView, { kind: 'signedOut' }>, repayOrderId: string | null = '42') =>
  renderToStaticMarkup(
    <PaymentResultView
      view={v}
      t={t}
      formatMoney={won}
      repayOrderId={repayOrderId}
      recheckBlocked={false}
      onRecheck={() => {}}
      onPollAgain={() => {}}
    />,
  );

describe('PaymentView', () => {
  it('서버에서는 결제 준비 중 안내만 그린다(위젯 · API 는 브라우저에서)', () => {
    const html = renderToStaticMarkup(<PaymentView orderId="42" clientKey="test_gck_x" />);
    expect(html).toContain('role="status"');
    expect(html).toContain(t.loading);
  });

  it('주문 요약은 서버 금액 · 주문명 · 결제 기한을 그대로 보인다', () => {
    const prepared: PaymentPrepared = { ...payment, paymentDueAt: '2026-11-02T01:30:00Z' };
    const html = renderToStaticMarkup(
      <PaymentSummary payment={prepared} formatMoney={won} dueAt="11월 2일 10:30" t={t}>
        <span>widget</span>
      </PaymentSummary>,
    );
    expect(html).toContain('39,000원');
    expect(html).toContain('저고리 외 1건');
    expect(html).toContain('11월 2일 10:30까지 결제하지 않으면');
  });

  it('READY 가 아니면 위젯 대신 — 완료는 안내, 확인 중은 다시 결제하지 말라는 안내', () => {
    expect(renderToStaticMarkup(<NotPayable outcome="done" t={t} />)).toContain(t.alreadyPaid);
    expect(renderToStaticMarkup(<NotPayable outcome="pending" t={t} />)).toContain(t.inProgress);
    expect(renderToStaticMarkup(<NotPayable outcome="other" t={t} />)).toContain(t.invalidOrder);
  });
});

describe('PaymentResult', () => {
  it('쿼리가 어긋나면(null) 승인 없이 확인 불가 안내 + 장바구니', () => {
    const html = renderToStaticMarkup(<PaymentResult result={null} returnPath="/cart" />);
    expect(html).toContain(t.invalidReturn);
    expect(html).toContain('href="/cart"');
  });

  it('쿼리가 맞으면 처음에는 확인 중 안내다', () => {
    const html = renderToStaticMarkup(
      <PaymentResult
        result={{
          confirm: {
            paymentKey: 'pk',
            pgOrderId: '501501',
            amount: { amount: 39000, currency: 'KRW' },
          },
          orderId: '42',
        }}
        returnPath="/checkout/payment/success"
      />,
    );
    expect(html).toContain(t.confirming);
  });

  it('완료 — 주문 번호 · 금액 · 결제수단(간편결제 제공자)', () => {
    const html = view({ kind: 'done', payment });
    expect(html).toContain(t.done);
    expect(html).toContain('42');
    expect(html).toContain('39,000원');
    expect(html).toContain('간편결제 (카카오페이)');
  });

  it('실패(FAILED) — 서버 사유 대신 사전 문구 + 그 주문의 다시 결제', () => {
    const html = view({
      kind: 'failed',
      payment: {
        ...payment,
        status: { code: 'FAILED', name: '', description: '' },
        failure: { code: 'REJECT_CARD_COMPANY', message: '서버 사유' },
      },
    });
    expect(html).toContain(t.failed);
    expect(html).not.toContain('서버 사유');
    expect(html).toContain('href="/checkout/payment?orderId=42"');
  });

  it('미확정 — 조회 중에는 안내만, 다 돌면 다시 확인 버튼(다시 결제 없음)', () => {
    const pending = { ...payment, status: { code: 'UNKNOWN', name: '', description: '' } };
    const polling = view({ kind: 'pending', payment: pending, exhausted: false });
    expect(polling).toContain(t.pending);
    expect(polling).not.toContain('<button');
    const exhausted = view({ kind: 'pending', payment: pending, exhausted: true });
    expect(exhausted).toContain(t.pendingLong);
    expect(exhausted).toContain(t.checkAgain);
    expect(exhausted).not.toContain(t.payAgain);
  });

  it('승인 실패 묶음별 — 다시 확인 · 다시 결제 · 불일치 · 장바구니', () => {
    const recheck = view({
      kind: 'error',
      failure: { kind: 'recheck', message: t.confirmRetry, retryAfterSec: null },
    });
    expect(recheck).toContain(t.checkAgain);
    expect(recheck).not.toContain(t.payAgain);

    expect(view({ kind: 'error', failure: { kind: 'repay', message: 'm' } })).toContain(
      'href="/checkout/payment?orderId=42"',
    );
    expect(view({ kind: 'error', failure: { kind: 'repay', message: 'm' } }, null)).toContain(
      'href="/cart"',
    );

    const mismatch = view({ kind: 'error', failure: { kind: 'mismatch', message: t.mismatch } });
    expect(mismatch).toContain(t.mismatch);
    expect(mismatch).not.toContain(t.payAgain);
    expect(mismatch).not.toContain('<button');

    expect(
      view({ kind: 'error', failure: { kind: 'final', message: 'm', cartLink: true } }),
    ).toContain('href="/cart"');
    expect(
      view({ kind: 'error', failure: { kind: 'final', message: 'm', cartLink: false } }),
    ).not.toContain('href=');
  });
});

describe('PaymentFailed', () => {
  it('사용자 취소 — 취소 문구로 다시 결제', () => {
    const html = renderToStaticMarkup(
      <PaymentFailed failure={{ code: 'PAY_PROCESS_CANCELED', orderId: '42' }} />,
    );
    expect(html).toContain(t.failCanceled);
    expect(html).toContain('href="/checkout/payment?orderId=42"');
  });

  it('그 밖 — 일반 문구만, 주문이 없으면 장바구니', () => {
    const html = renderToStaticMarkup(
      <PaymentFailed failure={{ code: 'REJECT_CARD_COMPANY', orderId: null }} />,
    );
    expect(html).toContain(t.failGeneric);
    expect(html).toContain('href="/cart"');
  });
});
