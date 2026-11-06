import { formatMoney, type Money } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import type { OrderDetail, OrderSummary } from '@/lib/orders/history';
import type { Delivery, DeliveryLoad } from '@/lib/orders/delivery';
import {
  CancelPanel,
  CancelProgress,
  ConfirmPurchasePanel,
  DeliveryView,
  OrderDetail as OrderDetailContainer,
  OrderDetailView,
  ReturnPanel,
} from './OrderDetail';
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

const shipped: Delivery = {
  deliveryId: '77',
  orderId: '42',
  carrier: { code: 'CJ_LOGISTICS', name: 'CJ대한통운', description: 'CJ대한통운' },
  trackingNumber: '123456789012',
  status: { code: 'IN_TRANSIT', name: '배송 중', description: '택배사가 상품을 옮기고 있습니다.' },
  shippedAt: '2026-11-02T09:30:00Z',
  deliveredAt: null,
};
const delivery = (load: DeliveryLoad, locale: 'ko' | 'en' = 'ko') =>
  renderToStaticMarkup(
    <DeliveryView
      load={load}
      t={locale === 'ko' ? t : en.orders}
      locale={locale}
      onRetry={() => {}}
    />,
  );

describe('DeliveryView', () => {
  it('불러오는 중이거나 송장이 없으면(null) 그리지 않는다', () => {
    expect(delivery({ status: 'loading' })).toBe('');
    expect(delivery({ status: 'ready', delivery: null })).toBe('');
  });

  it('택배사 · 송장 번호 · 상태 · 출고 일시와 새 창 조회 링크', () => {
    const html = delivery({ status: 'ready', delivery: shipped });
    expect(html).toContain(t.delivery);
    expect(html).toContain('CJ대한통운');
    expect(html).toContain('123456789012');
    expect(html).toContain('배송 중');
    expect(html).toContain('택배사가 상품을 옮기고 있습니다.');
    expect(html).toContain(t.shippedAt);
    expect(html).not.toContain(t.deliveredAt);
    expect(html).toContain(
      'href="https://trace.cjlogistics.com/next/tracking.html?wblNo=123456789012" target="_blank" rel="noopener noreferrer"',
    );
    expect(html).toContain(t.newWindow);
  });

  it('배송 완료면 완료 일시, 기타 택배사는 링크 없이 송장 번호만', () => {
    const html = delivery({
      status: 'ready',
      delivery: {
        ...shipped,
        carrier: { code: 'OTHER', name: '기타 택배사', description: '기타 택배사' },
        status: { code: 'DELIVERED', name: '배송 완료', description: '상품이 배송되었습니다.' },
        deliveredAt: '2026-11-04T02:10:00Z',
      },
    });
    expect(html).toContain(t.deliveredAt);
    expect(html).toContain('123456789012');
    expect(html).not.toContain('<a ');
  });

  it('영문 스토어는 사전 이름을 쓰고 서버 국문 설명을 숨긴다', () => {
    const html = delivery({ status: 'ready', delivery: shipped }, 'en');
    expect(html).toContain('In transit');
    expect(html).toContain('CJ Logistics');
    expect(html).not.toContain('택배사가 상품을 옮기고 있습니다.');
  });

  it('실패하면 안내와 다시 불러오기 — 다시 읽는 중에는 버튼을 남긴 채 막는다', () => {
    const html = delivery({ status: 'failed', retrying: false });
    expect(html).toContain(t.deliveryLoadFailed);
    expect(html).toContain(t.reload);
    expect(html).not.toContain('disabled=""');
    expect(delivery({ status: 'failed', retrying: true })).toContain('disabled=""');
  });

  it('상세의 상태 영역 다음 · 주문 상품 앞에 놓인다', () => {
    const html = renderToStaticMarkup(
      <OrderDetailView
        order={detail({ status: paid, cancelable: false })}
        now={NOW}
        t={t}
        locale="ko"
        formatMoney={krw}
        shipment={
          <DeliveryView
            load={{ status: 'ready', delivery: shipped }}
            t={t}
            locale="ko"
            onRetry={() => {}}
          />
        }
      />,
    );
    expect(html.indexOf('order-delivery')).toBeGreaterThan(html.indexOf('결제 완료'));
    expect(html.indexOf('order-delivery')).toBeLessThan(html.indexOf('order-items'));
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

  it('결제 후 취소는 전액 환불 안내로 바꾼다', () => {
    const html = panel({ paid: true });
    expect(html).toContain(t.cancelPaidCaution);
    expect(html).not.toContain(t.cancelCaution);
  });
});

describe('CancelProgress', () => {
  it('처리 중 안내와 다시 확인 버튼을 두고, 다시 읽는 동안 막는다', () => {
    const html = renderToStaticMarkup(
      <CancelProgress t={t} rechecking={false} onRecheck={() => {}} />,
    );
    expect(html).toContain(t.cancelInProgress);
    expect(html).toContain(t.cancelRecheck);
    expect(renderToStaticMarkup(<CancelProgress t={t} rechecking onRecheck={() => {}} />)).toMatch(
      /<button[^>]*disabled=""/,
    );
  });
});

describe('ConfirmPurchasePanel', () => {
  const panel = (props: Partial<Parameters<typeof ConfirmPurchasePanel>[0]> = {}) =>
    renderToStaticMarkup(
      <ConfirmPurchasePanel
        t={t}
        error={null}
        confirming={false}
        blocked={false}
        onConfirm={() => {}}
        onClose={() => {}}
        {...props}
      />,
    );

  it('반품 · 교환 불가 안내와 확정하기 · 닫기', () => {
    const html = panel();
    expect(html).toContain(t.confirmTitle);
    expect(html).toContain(t.confirmCaution);
    expect(html).not.toMatch(/<button[^>]*disabled=""[^>]*>확정하기/);
    expect(html).toContain(t.confirmClose);
  });

  it('진행 중이면 두 버튼을 막고, 쿨다운이면 확정하기를 막는다', () => {
    const busy = panel({ confirming: true });
    expect(busy).toContain(t.confirming);
    expect(busy).toMatch(/<button[^>]*disabled=""[^>]*>닫기/);
    expect(panel({ blocked: true })).toMatch(/<button[^>]*disabled=""[^>]*>확정하기/);
  });

  it('실패 문구는 alert 다', () => {
    expect(panel({ error: t.confirmFailed })).toContain('role="alert"');
  });
});

describe('ReturnPanel', () => {
  const lines = [
    { ...detail().items[0]!, orderItemId: '11', quantity: 3 },
    { ...detail().items[0]!, orderItemId: '12', productName: '생활한복 바지', quantity: 1 },
  ];
  const panel = (props: Partial<Parameters<typeof ReturnPanel>[0]> = {}) =>
    renderToStaticMarkup(
      <ReturnPanel
        t={t}
        lines={lines}
        selected={{}}
        onSelect={() => {}}
        reason={null}
        onReason={() => {}}
        error={null}
        ready={false}
        returning={false}
        blocked={false}
        onConfirm={() => {}}
        onClose={() => {}}
        {...props}
      />,
    );

  it('항목 · 사유 세 가지 · 안내를 보이고, 고르기 전에는 접수하기를 막는다', () => {
    const html = panel();
    expect(html).toContain('모시 저고리');
    expect(html).toContain('생활한복 바지');
    for (const label of Object.values(t.returnReasons)) expect(html).toContain(label);
    expect(html).toContain(t.returnCaution);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>반품 접수하기<\/button>/);
  });

  it('수량 선택은 2개 이상 주문한 항목만, 고르기 전에는 막는다(1 ~ 주문 수량)', () => {
    const html = panel();
    expect(html.match(/<select/g)).toHaveLength(1);
    expect(html).toContain('aria-label="모시 저고리 반품 수량"');
    expect(html).toMatch(/<select[^>]*disabled=""/);
    expect(html).toContain('<option value="3"');
    expect(html).not.toContain('<option value="4"');
    expect(panel({ selected: { '11': 2 } })).not.toMatch(/<select[^>]*disabled=""/);
  });

  it('본문이 준비되면 누를 수 있고, 쿨다운 · 진행 중이면 막는다', () => {
    expect(panel({ ready: true })).not.toMatch(/<button[^>]*disabled=""[^>]*>반품 접수하기/);
    expect(panel({ ready: true, blocked: true })).toMatch(
      /<button[^>]*disabled=""[^>]*>반품 접수하기/,
    );
    const busy = panel({ ready: true, returning: true });
    expect(busy).toContain(t.returning);
    expect(busy).toMatch(/<button[^>]*disabled=""[^>]*>닫기/);
  });

  it('실패 문구는 alert 다', () => {
    expect(panel({ error: t.returnFailed })).toContain('role="alert"');
  });
});
