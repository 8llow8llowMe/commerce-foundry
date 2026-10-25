import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import type { CartLineView } from '@/lib/cart/line-view';
import { en } from '@/lib/i18n/dictionaries/en';
import { CartLineItem } from './CartLineItem';

const line = (overrides: Partial<CartLineView> = {}): CartLineView => ({
  key: '1',
  productId: '10',
  name: '저고리',
  optionName: '아이보리 / L',
  quantity: 1,
  maxQuantity: 3,
  unitPrice: { amount: 100000, currency: 'KRW' },
  lineAmount: null,
  status: { code: 'ON_SALE', name: '판매중', description: '' },
  quantityExceeded: false,
  customOptions: [],
  preorder: null,
  ...overrides,
});

const render = (overrides?: Partial<CartLineView>) =>
  renderToStaticMarkup(
    <CartLineItem line={line(overrides)} busy={false} onQuantity={() => {}} onRemove={() => {}} />,
  );

describe('CartLineItem 프리오더', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 9, 25));
  });
  afterEach(() => vi.useRealTimers());

  it('프리오더 줄은 옵션 아래에 발송 예정을 보인다', () => {
    const html = render({ preorder: { expectedShipDate: '2026-11-20' } });
    expect(html).toContain('<p class="text-xs">프리오더 · 11월 20일부터 순차 발송</p>');
    expect(html.indexOf('프리오더')).toBeGreaterThan(html.indexOf('아이보리 / L'));
  });

  it('발송 예정일이 없으면 추후 안내, 일반 상품은 안내가 없다', () => {
    expect(render({ preorder: { expectedShipDate: null } })).toContain(
      '프리오더 · 발송일 추후 안내',
    );
    expect(render()).not.toContain('프리오더');
  });

  it('영문 스토어는 영문 날짜로 보인다', () => {
    const html = renderToStaticMarkup(
      <LocaleProvider locale="en" prefix="/en" dict={en}>
        <CartLineItem
          line={line({ preorder: { expectedShipDate: '2027-01-05' } })}
          busy={false}
          onQuantity={() => {}}
          onRemove={() => {}}
        />
      </LocaleProvider>,
    );
    expect(html).toContain('Pre-order · Ships from January 5, 2027');
  });
});
