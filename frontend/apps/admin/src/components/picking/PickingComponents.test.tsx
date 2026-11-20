import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { orderItem } from '@/lib/order/test-fixtures';
import { pickingBatch } from '@/lib/picking/test-fixtures';
import { OrderCheckCard } from './OrderCheckCard';
import { PickingBatchPanel } from './PickingBatchPanel';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const noop = () => {};

describe('OrderCheckCard', () => {
  const batch = pickingBatch({
    checks: [
      {
        orderId: '1',
        successful: false,
        reason: { code: 'SHORTAGE', name: '수량 부족', description: '1개 모자랍니다' },
        quantities: [],
      },
    ],
  });

  it('미검수 · 실패 주문은 주문 수량 옆에 센 수량 칸 · 마지막 실패 사유 · 다른 옵션 고르기를 보인다', () => {
    const html = renderToStaticMarkup(
      <OrderCheckCard
        batch={batch}
        order={batch.orders[0]!}
        labels={{ '10': '저고리 · M', '11': '치마' }}
        counts={{ '10': '1' }}
        extraSkuIds={[]}
        errors={{ '10': '정수로 입력해 주세요.' }}
        busy={false}
        onCount={noop}
        onAddExtra={noop}
        onCheck={noop}
      />,
    );
    expect(html).toContain('검수 실패');
    expect(html).toContain('마지막 검수: 수량 부족 — 1개 모자랍니다');
    expect(html).toContain('저고리 · M');
    expect(html).toContain('value="1"');
    expect(html).toContain('aria-invalid="true"');
    expect(html).toContain('정수로 입력해 주세요.');
    // 주문에 없는 묶음 옵션(11)만 고를 수 있다
    expect(html).toContain('<option value="11">치마</option>');
    expect(html).not.toContain('<option value="10">');
    expect(html).toContain('이 주문 검수');
  });

  it('주문에 없는 옵션을 고르면 줄로 붙고, 검수 완료 주문은 입력 없이 안내만 보인다', () => {
    const extra = renderToStaticMarkup(
      <OrderCheckCard
        batch={batch}
        order={batch.orders[0]!}
        labels={{}}
        counts={{}}
        extraSkuIds={['11']}
        errors={{}}
        busy
        onCount={noop}
        onAddExtra={noop}
        onCheck={noop}
      />,
    );
    expect(extra).toContain('주문에 없는 옵션');
    expect(extra).toContain('옵션 ID 11');
    expect(extra).toContain('검수 중…');

    const done = renderToStaticMarkup(
      <OrderCheckCard
        batch={batch}
        order={batch.orders[1]!}
        labels={{}}
        counts={{}}
        extraSkuIds={[]}
        errors={{}}
        busy={false}
        onCount={noop}
        onAddExtra={noop}
        onCheck={noop}
      />,
    );
    expect(done).toContain('검수 완료');
    expect(done).not.toContain('<input');
  });
});

describe('PickingBatchPanel', () => {
  it('고른 주문이 없으면 버튼을 막고, 20건을 넘으면 오류를 보인다', () => {
    const empty = renderToStaticMarkup(<PickingBatchPanel selected={[]} onDenied={noop} />);
    expect(empty).toContain('disabled=""');
    expect(empty).toContain('data-tour="order-picking"');

    const many = Array.from({ length: 21 }, (_, index) => orderItem({ orderId: String(index) }));
    const over = renderToStaticMarkup(<PickingBatchPanel selected={many} onDenied={noop} />);
    expect(over).toContain('고른 주문 21건');
    expect(over).toContain('20건까지 담을 수 있습니다');
  });
});
