import { formatMoney, type Money } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import type { OrderDetail, OrderSummary } from '@/lib/orders/history';
import { CancelPanel, OrderDetail as OrderDetailContainer, OrderDetailView } from './OrderDetail';
import { OrderList, OrderListView } from './OrderList';

const t = ko.orders;
const NOW = Date.parse('2026-11-02T01:00:00Z');
const won = (amount: number): Money => ({ amount, currency: 'KRW' });
const krw = (money: Money) => formatMoney(money, 'ko');
const pending = { code: 'PENDING_PAYMENT', name: '결제 대기', description: '결제를 기다립니다.' };
const paid = { code: 'PAID', name: '결제 완료', description: '결제가 완료되었습니다.' };

const summary = (orderId: string, patch: Partial<OrderSummary> = {}): OrderSummary => ({
  orderId,
  status: pending,
  orderName: '저고리 외 1건',
  itemCount: 2,
  totalAmount: won(39000),
  cancelable: true,
  paymentDueAt: '2026-11-02T01:30:00Z',
  createdAt: '2026-11-02T00:55:00Z',
  ...patch,
});

const detail = (patch: Partial<OrderDetail> = {}): OrderDetail => ({
  orderId: '42',
  status: pending,
  cancelable: true,
  paymentDueAt: '2026-11-02T01:30:00Z',
  currency: 'KRW',
  items: [
    {
      skuId: '9001',
      productId: '1001',
      productName: '모시 저고리',
      optionName: '네이비 / M',
      unitPrice: won(36000),
      quantity: 1,
      lineAmount: won(36000),
      customOrder: false,
      customOptions: [],
    },
  ],
  amounts: { itemsAmount: won(36000), shippingFee: won(3000), totalAmount: won(39000) },
  shipping: {
    scope: 'DOMESTIC',
    baseFee: won(3000),
    freeShippingApplied: false,
    remoteAreaSurcharge: won(0),
    remoteAreaName: null,
    freeShippingThreshold: null,
    weightFee: null,
    shippingZoneName: null,
  },
  shippingAddress: {
    recipientName: '홍길동',
    recipientPhone: '01012345678',
    countryCode: 'KR',
    postalCode: '06236',
    addressLine1: '서울 강남구 테헤란로 1',
    addressLine2: null,
    city: null,
    stateProvince: null,
    deliveryMemo: '문 앞',
  },
  createdAt: '2026-11-02T00:55:00Z',
  ...patch,
});

const list = (orders: OrderSummary[]) =>
  renderToStaticMarkup(
    <OrderListView orders={orders} now={NOW} t={t} locale="ko" formatMoney={krw} />,
  );
const view = (order: OrderDetail, locale: 'ko' | 'en' = 'ko') =>
  renderToStaticMarkup(
    <OrderDetailView
      order={order}
      now={NOW}
      t={locale === 'ko' ? t : en.orders}
      locale={locale}
      formatMoney={krw}
    />,
  );

describe('OrderList', () => {
  it('서버에서는 불러오는 중 안내만 그린다(목록은 브라우저에서 BFF 로)', () => {
    const html = renderToStaticMarkup(<OrderList />);
    expect(html).toContain('role="status"');
    expect(html).toContain(t.loading);
  });

  it('주문이 없으면 빈 안내와 쇼핑 링크', () => {
    const html = list([]);
    expect(html).toContain(t.empty);
    expect(html).toContain('href="/shop"');
  });

  it('상태 · 주문 이름 · 서버 금액 · 상세 링크를 보인다', () => {
    const html = list([summary('42')]);
    expect(html).toContain('결제 대기');
    expect(html).toContain('저고리 외 1건');
    expect(html).toContain('39,000원');
    expect(html).toContain('href="/mypage/orders/42"');
  });

  it('결제 대기 · 기한 전에만 결제 이어하기 — 결제 화면으로', () => {
    expect(list([summary('42')])).toContain('href="/checkout/payment?orderId=42"');
    const expired = list([summary('42', { paymentDueAt: '2026-11-02T00:59:00Z' })]);
    expect(expired).not.toContain(t.resumePayment);
    const paidHtml = list([summary('42', { status: paid, cancelable: false })]);
    expect(paidHtml).not.toContain(t.resumePayment);
  });

  it('주문 이름이 없으면 항목 수', () => {
    expect(list([summary('42', { orderName: null })])).toContain('상품 2개');
  });
});

describe('OrderDetail', () => {
  it('서버에서는 불러오는 중 안내만 그린다', () => {
    const html = renderToStaticMarkup(<OrderDetailContainer orderId="42" />);
    expect(html).toContain(t.loading);
  });

  it('항목 · 금액(서버 값) · 배송지를 보인다', () => {
    const html = view(detail());
    expect(html).toContain('모시 저고리');
    expect(html).toContain('네이비 / M');
    expect(html).toContain('36,000원');
    expect(html).toContain('3,000원');
    expect(html).toContain('39,000원');
    expect(html).toContain('홍길동');
    expect(html).toContain('010-1234-5678');
    expect(html).toContain('문 앞');
    expect(html).toContain('결제를 기다립니다.');
  });

  it('결제 대기 · 기한 전에만 결제 이어하기', () => {
    expect(view(detail())).toContain('href="/checkout/payment?orderId=42"');
    expect(view(detail({ paymentDueAt: '2026-11-02T00:00:00Z' }))).not.toContain(t.resumePayment);
    expect(view(detail({ status: paid, cancelable: false }))).not.toContain(t.resumePayment);
  });

  it('영문 스토어는 사전 상태 이름을 쓰고 서버 국문 설명을 숨긴다', () => {
    const html = view(detail(), 'en');
    expect(html).toContain('Awaiting payment');
    expect(html).not.toContain('결제를 기다립니다.');
  });
});

describe('CancelPanel', () => {
  const panel = (props: Partial<Parameters<typeof CancelPanel>[0]> = {}) =>
    renderToStaticMarkup(
      <CancelPanel
        t={t}
        reason={null}
        onReason={() => {}}
        error={null}
        canceling={false}
        blocked={false}
        onConfirm={() => {}}
        onClose={() => {}}
        {...props}
      />,
    );

  it('사유 네 가지를 보이고, 고르기 전에는 취소하기를 막는다', () => {
    const html = panel();
    for (const label of Object.values(t.cancelReasons)) expect(html).toContain(label);
    expect(html).toContain(t.cancelCaution);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>취소하기<\/button>/);
  });

  it('사유를 고르면 누를 수 있고, 쿨다운 · 진행 중이면 막는다', () => {
    expect(panel({ reason: 'OTHER' })).not.toMatch(/<button[^>]*disabled=""[^>]*>취소하기/);
    expect(panel({ reason: 'OTHER', blocked: true })).toMatch(
      /<button[^>]*disabled=""[^>]*>취소하기/,
    );
    expect(panel({ reason: 'OTHER', canceling: true })).toContain(t.canceling);
  });

  it('실패 문구는 alert 다', () => {
    expect(panel({ error: t.cancelFailed })).toContain('role="alert"');
  });
});
