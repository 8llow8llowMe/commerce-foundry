import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { bulkResponse, orderDetail, orderItem } from '@/lib/order/test-fixtures';
import { BulkResult } from './BulkResult';
import { OrderDetailView } from './OrderDetailView';
import { OrderTable } from './OrderTable';
import { ShipmentBulkPanel } from './ShipmentBulkPanel';
import { ShipmentPanel } from './ShipmentPanel';
import { ShipmentResult } from './ShipmentResult';
import { StatusChangePanel } from './StatusChangePanel';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const noop = () => {};

describe('OrderTable', () => {
  it('상세 링크 · 서버 상태 name · 가린 받는 분 · formatMoney 금액 · 고른 표시를 보인다', () => {
    const html = renderToStaticMarkup(
      <OrderTable
        orders={[orderItem(), orderItem({ orderId: '2', orderName: '두 번째' })]}
        selected={new Set(['2'])}
        disabled
        onToggle={noop}
        onToggleAll={noop}
      />,
    );
    expect(html).toContain('href="/orders/4611686018427387904"');
    expect(html).toContain('>결제 완료<');
    expect(html).toContain('title="결제 완료 설명"');
    expect(html).toContain('홍*동');
    expect(html).toContain('93,000원');
    // 두 번째 줄만 체크 — "모두 고르기" 는 꺼져 있다
    expect(html.match(/checked=""/g)).toHaveLength(1);
    // 다시 읽는 중에는 고르기를 막는다
    expect(html.match(/disabled=""/g)).toHaveLength(3);
  });
});

describe('BulkResult', () => {
  it('요약 · 행별 결과 · 서버 실패 이유 · 실패만 다시 시도를 보인다', () => {
    const html = renderToStaticMarkup(
      <BulkResult
        outcome={{
          request: { orderIds: ['11', '12', '13'], status: 'PREPARING', reason: null },
          response: bulkResponse(),
          names: { '11': '첫 주문', '12': '둘째 주문' },
        }}
        retrying={false}
        onRetryFailed={noop}
        onDismiss={noop}
      />,
    );
    expect(html).toContain('상품 준비중(으)로 변경: 일부 주문을 바꾸지 못했습니다');
    expect(html).toContain('첫 주문');
    // 이름을 모르는 주문은 아이디로
    expect(html).toContain('href="/orders/13"');
    expect(html).toContain('이미 그 상태');
    expect(html).toContain('현재 주문 상태(취소 완료)에서는 할 수 없는 요청입니다.');
    // 지금 상태에서 할 수 없는 실패(ORDER_020)는 다시 시도에서 뺀다
    expect(html).not.toContain('건만 다시 시도');
    expect(html).toContain('다시 보내도 같은 결과');
  });

  it('경합 실패(ORDER_025)가 있으면 그 건만 다시 시도를 둔다', () => {
    const html = renderToStaticMarkup(
      <BulkResult
        outcome={{
          request: { orderIds: ['11', '13'], status: 'PREPARING', reason: null },
          response: bulkResponse({
            changedCount: 1,
            unchangedCount: 0,
            failedCount: 1,
            results: [
              { orderId: '11', result: 'CHANGED', errorCode: null, message: null },
              { orderId: '13', result: 'FAILED', errorCode: 'ORDER_025', message: '경합' },
            ],
          }),
          names: {},
        }}
        retrying={false}
        onRetryFailed={noop}
        onDismiss={noop}
      />,
    );
    expect(html).toContain('실패한 1건만 다시 시도');
  });

  it('실패가 없으면 다시 시도를 두지 않는다', () => {
    const html = renderToStaticMarkup(
      <BulkResult
        outcome={{
          request: { orderIds: ['11'], status: 'MAKING', reason: null },
          response: bulkResponse({
            changedCount: 1,
            unchangedCount: 0,
            failedCount: 0,
            results: [{ orderId: '11', result: 'CHANGED', errorCode: null, message: null }],
          }),
          names: {},
        }}
        retrying={false}
        onRetryFailed={noop}
        onDismiss={noop}
      />,
    );
    expect(html).toContain('모두 처리했습니다');
    expect(html).not.toContain('다시 시도');
  });
});

describe('StatusChangePanel', () => {
  const panel = (order = orderDetail()) =>
    renderToStaticMarkup(
      <StatusChangePanel
        order={order}
        onChanged={noop}
        onStale={noop}
        onNotFound={noop}
        onDenied={noop}
        onSubmit={noop}
      />,
    );

  it('서버가 준 nextStatuses 만 버튼으로 그린다', () => {
    const html = panel();
    expect(html).toContain('제작 중(으)로 변경');
    expect(html).toContain('상품 준비중(으)로 변경');
    expect(html).not.toContain('배송 완료(으)로 변경');
  });

  it('바꿀 수 있는 상태가 없으면 버튼 없이 안내한다', () => {
    const html = panel(orderDetail({ nextStatuses: [] }));
    expect(html).not.toContain('<button');
    expect(html).toContain('바꿀 수 있는 상태가 없습니다');
  });
});

describe('OrderDetailView', () => {
  it('항목 · 맞춤 제작 입력 · 서버 금액 · 가린 배송지 · 이력을 보인다', () => {
    const html = renderToStaticMarkup(<OrderDetailView order={orderDetail()} />);
    expect(html).toContain('모시 저고리');
    expect(html).toContain('M · 쪽빛');
    expect(html).toContain('맞춤 제작 · chest 96.5');
    expect(html).toContain('90,000원');
    expect(html).toContain('3,000원');
    expect(html).toContain('93,000원');
    expect(html).toContain('010****5678');
    expect(html).toContain('결제 대기(주문 접수)');
    expect(html).toContain('결제 대기 → 결제 완료');
    expect(html).toContain('연동 처리');
  });
});

describe('ShipmentPanel', () => {
  it('택배사 목록 · 송장 번호 칸 · 등록 버튼을 그린다', () => {
    const html = renderToStaticMarkup(
      <ShipmentPanel orderId="1" onResult={noop} onDenied={noop} onSubmit={noop} />,
    );
    expect(html).toContain('송장 등록 (출고)');
    expect(html).toContain('<option value="CJ_LOGISTICS">CJ대한통운</option>');
    expect(html).toContain('<option value="OTHER">기타 택배사</option>');
    expect(html).toContain('송장 번호');
    expect(html).toContain('>송장 등록<');
  });
});

describe('ShipmentBulkPanel', () => {
  it('고른 주문이 없으면 넣기 · 등록을 막고 붙여넣기 안내를 보인다', () => {
    const html = renderToStaticMarkup(
      <ShipmentBulkPanel selected={[]} onSent={noop} onDenied={noop} />,
    );
    expect(html).toContain('송장 일괄 등록 (출고)');
    expect(html).toContain('표에서 주문을 고르세요');
    expect(html).toContain('열 순서: 주문 아이디 · 택배사(이름 또는 코드) · 송장 번호');
    expect(html).toContain('송장 0건 등록');
    expect(html.match(/disabled=""/g)?.length).toBeGreaterThanOrEqual(3);
  });

  it('고른 주문 수를 넣기 버튼에 보인다', () => {
    const html = renderToStaticMarkup(
      <ShipmentBulkPanel selected={[orderItem()]} onSent={noop} onDenied={noop} />,
    );
    expect(html).toContain('고른 주문 1건 넣기');
  });
});

describe('ShipmentResult', () => {
  it('요약 · 송장 · 행별 결과 · 서버 실패 이유와 남긴 줄 안내를 보인다', () => {
    const html = renderToStaticMarkup(
      <ShipmentResult
        rows={[
          {
            item: { orderId: '11', carrier: 'CJ_LOGISTICS', trackingNumber: '111' },
            result: 'REGISTERED',
            errorCode: null,
            message: null,
          },
          {
            item: { orderId: '12', carrier: 'HANJIN', trackingNumber: '222' },
            result: 'FAILED',
            errorCode: 'DELIVERY_018',
            message: '주문에 출고를 반영하지 못했습니다. 잠시 후 같은 송장으로 다시 등록해 주세요.',
          },
          {
            item: { orderId: '13', carrier: 'LOTTE', trackingNumber: '333' },
            result: 'FAILED',
            errorCode: 'DELIVERY_015',
            message: '취소 요청 상태라 출고할 수 없습니다.',
          },
        ]}
        names={{ '11': '첫 주문' }}
        disabled={false}
        onDismiss={noop}
      />,
    );
    expect(html).toContain('송장 등록: 일부 주문을 출고하지 못했습니다 — 등록 1건 · 실패 2건');
    expect(html).toContain('첫 주문');
    // 이름을 모르는 주문은 아이디로
    expect(html).toContain('href="/orders/12"');
    expect(html).toContain('CJ대한통운 111');
    expect(html).toContain('취소 요청 상태라 출고할 수 없습니다.');
    expect(html).toContain('다시 보낼 수 있는 1건');
    expect(html).toContain('다시 보내도 같은 결과라');
  });
});
