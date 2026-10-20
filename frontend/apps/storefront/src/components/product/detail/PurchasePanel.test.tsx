import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { ProductSku, SaleStatusCode } from '@/lib/catalog/types';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import { en } from '@/lib/i18n/dictionaries/en';
import { PurchasePanel, type PurchaseProduct } from './PurchasePanel';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }) }));

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: SaleStatusCode, name: string = code) => ({ code, name, description: '' });
const sku = (
  skuId: string,
  ids: string[],
  add: number,
  code: SaleStatusCode = 'ON_SALE',
): ProductSku => ({
  skuId,
  optionValueIds: ids,
  additionalPrice: krw(add),
  salePrice: krw(129000 + add),
  saleStatus: status(code),
});

const product = (overrides: Partial<PurchaseProduct> = {}): PurchaseProduct => ({
  productId: '1',
  name: '윤금 드레스',
  summary: null,
  thumbnail: null,
  salePrice: krw(129000),
  listPrice: krw(159000),
  discountRate: 18,
  saleStatus: status('ON_SALE', '판매중'),
  maxQuantityPerOrder: 3,
  optionGroups: [
    {
      optionGroupId: 'size',
      name: '사이즈',
      displayOrder: 0,
      values: [
        { optionValueId: 's', name: 'S', displayOrder: 0 },
        { optionValueId: 'm', name: 'M', displayOrder: 1 },
        { optionValueId: 'l', name: 'L', displayOrder: 2 },
      ],
    },
  ],
  skus: [sku('s', ['s'], 0), sku('m', ['m'], 0, 'SOLD_OUT'), sku('l', ['l'], 10000)],
  customOrder: null,
  ...overrides,
});

const render = (overrides?: Partial<PurchaseProduct>) =>
  renderToStaticMarkup(<PurchasePanel product={product(overrides)} />);

/** 옵션 라디오 하나의 input 태그 */
const radio = (html: string, value: string) =>
  html.match(new RegExp(`<input[^>]*value="${value}"[^>]*>`))?.[0] ?? '';

describe('PurchasePanel (첫 렌더)', () => {
  it('옵션을 고르기 전에는 상품 최저가 · 정가 · 할인율에 ~ 를 붙인다', () => {
    const html = render();
    expect(html).toContain('18%');
    expect(html).toContain('129,000원<span aria-hidden="true">~</span>');
    expect(html).toMatch(/<del[^>]*>.*159,000원<\/del>/);
  });

  it('품절 SKU 만 있는 값은 막고 스크린리더에 품절을 알린다', () => {
    const html = render();
    expect(radio(html, 's')).not.toContain('disabled');
    expect(radio(html, 'm')).toContain('disabled');
    expect(html).toContain('(품절)');
  });

  it('옵션을 고르라고 안내하고 장바구니 버튼은 아직 비활성이다', () => {
    const html = render();
    expect(html).toContain('옵션을 선택해 주세요.');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>장바구니 담기<\/button>/);
    expect(html).toContain('한 번에 최대 3개까지 주문할 수 있습니다.');
  });

  it('판매 종료 상품은 서버 상태 문구를 안내한다', () => {
    expect(render({ saleStatus: status('STOP_SELLING', '판매 종료') })).toContain('>판매 종료</p>');
  });

  it('옵션 없는 상품은 옵션 줄 없이 하나뿐인 SKU 로 바로 정해져 담을 수 있다', () => {
    const html = render({ optionGroups: [], skus: [sku('only', [], 0)] });
    expect(html).not.toContain('type="radio"');
    expect(html).not.toContain('~');
    expect(html).toContain('선택한 옵션을 담을 수 있습니다.');
    expect(html).toMatch(/<button(?![^>]*disabled="")[^>]*>장바구니 담기<\/button>/);
  });

  it('맞춤 제작이면 제작 기간 · 안내 · 입력 항목(단위 · 필수 · 도움말)을 그린다', () => {
    const html = render({
      customOrder: {
        notice: '주문 후 제작합니다.',
        leadTimeDays: 14,
        fields: [
          {
            fieldKey: 'waist',
            label: '허리 둘레',
            inputType: 'NUMBER',
            unit: 'cm',
            required: true,
            min: 50,
            max: 120,
            step: 0.5,
            helpText: '배꼽 위치에서 잽니다.',
          },
        ],
      },
    });
    expect(html).toContain('제작 기간 약 14일');
    expect(html).toContain('주문 후 제작합니다.');
    const input = html.match(/<input[^>]*name="waist"[^>]*>/)?.[0] ?? '';
    for (const attr of ['type="number"', 'required=""', 'min="50"', 'max="120"', 'step="0.5"']) {
      expect(input).toContain(attr);
    }
    expect(html).toContain('(cm)');
    expect(html).toContain('배꼽 위치에서 잽니다.');
  });

  it('맞춤 제작 필수 칸이 비어 있으면 SKU 가 정해져도 담기를 막고 안내한다', () => {
    const html = render({
      optionGroups: [],
      skus: [sku('only', [], 0)],
      customOrder: {
        notice: null,
        leadTimeDays: null,
        fields: [
          {
            fieldKey: 'waist',
            label: '허리 둘레',
            inputType: 'NUMBER',
            unit: 'cm',
            required: true,
            min: null,
            max: null,
            step: null,
            helpText: null,
          },
        ],
      },
    });
    expect(html).toContain('맞춤 제작 필수 항목을 입력해 주세요.');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>장바구니 담기<\/button>/);
  });
});

describe('PurchasePanel 모바일 하단 구매 바', () => {
  /** 화면 아래 고정 바의 마크업 */
  const bar = (html: string) =>
    html.match(/<div[^>]*class="fixed inset-x-0 bottom-0[\s\S]*$/)?.[0] ?? '';

  it('PC 에서는 숨기고, 상품명 · 표시가 · 구매하기 버튼을 둔다', () => {
    const html = bar(render());
    expect(html).toContain('lg:hidden');
    expect(html).toContain('윤금 드레스');
    expect(html).toContain('129,000원<span aria-hidden="true">~</span>');
    expect(html).toMatch(/<button[^>]*>구매하기<\/button>/);
  });

  it('판매중이 아니면 버튼에 서버 상태 문구를 쓴다', () => {
    const html = bar(render({ saleStatus: status('SOLD_OUT', '품절') }));
    expect(html).toMatch(/<button[^>]*>품절<\/button>/);
  });
});

describe('PurchasePanel (영문 스토어)', () => {
  it('문구 · 수량 안내 · 금액을 영문 스토어 표기로 보인다', () => {
    const html = renderToStaticMarkup(
      <LocaleProvider locale="en" prefix="/en" dict={en}>
        <PurchasePanel product={product()} />
      </LocaleProvider>,
    ).replaceAll('<!-- -->', '');
    expect(html).toContain('₩129,000');
    expect(html).toContain('Please select your options.');
    expect(html).toContain('You can order up to 3 per order.');
    expect(html).toContain('aria-label="Increase quantity"');
    expect(html).toContain('Add to bag');
    expect(html).not.toMatch(/장바구니|수량|옵션을/);
  });
});
