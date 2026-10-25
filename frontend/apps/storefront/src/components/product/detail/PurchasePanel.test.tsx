import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { DeliveryNote } from '@/lib/catalog/delivery-note';
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

const render = (overrides?: Partial<PurchaseProduct>, delivery?: DeliveryNote | null) =>
  renderToStaticMarkup(<PurchasePanel product={product(overrides)} delivery={delivery} />);

/** 화면 아래 고정 바의 마크업 (뒤따르는 바텀시트는 뺀다) */
const bar = (html: string) =>
  html.match(/<div[^>]*class="fixed inset-x-0 bottom-0[\s\S]*?(?=<dialog)/)?.[0] ?? '';
/** 모바일 구매 바텀시트의 마크업 */
const sheet = (html: string) => html.match(/<dialog[\s\S]*<\/dialog>/)?.[0] ?? '';
/** 라디오 묶음(name) */
const radioNames = (markup: string) =>
  new Set(
    [...markup.matchAll(/<input[^>]*type="radio"[^>]*>/g)].map(
      (m) => /name="([^"]+)"/.exec(m[0])?.[1],
    ),
  );

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

describe('PurchasePanel 배송 안내', () => {
  const delivery: DeliveryNote = { lines: ['배송비 3,000원', '50,000원 이상 구매 시 무료배송'] };

  it('가격 바로 아래(옵션 위)에 안내 줄을 모두 보이고, 하단 바에는 첫 줄만 보인다', () => {
    const html = render({}, delivery);
    const line = html.indexOf('<li>배송비 3,000원</li>');
    expect(line).toBeGreaterThan(html.indexOf('129,000원'));
    expect(line).toBeLessThan(html.indexOf('type="radio"'));
    expect(html).toContain('<li>50,000원 이상 구매 시 무료배송</li>');
    const bottom = bar(html);
    expect(bottom).toContain('>배송비 3,000원</span>');
    expect(bottom).not.toContain('무료배송');
  });

  it('안내가 없으면 줄을 그리지 않는다', () => {
    const html = render();
    expect(html).not.toContain('<li>');
    expect(bar(html)).not.toContain('truncate');
  });
});

describe('PurchasePanel 모바일 하단 구매 바', () => {
  it('PC 에서는 숨기고, 표시가 · 구매하기 버튼을 둔다', () => {
    const html = bar(render());
    expect(html).toContain('lg:hidden');
    expect(html).toContain('129,000원<span aria-hidden="true">~</span>');
    expect(html).toMatch(/<button[^>]*>구매하기<\/button>/);
  });

  it('판매중이 아니면 버튼에 서버 상태 문구를 쓴다', () => {
    const html = bar(render({ saleStatus: status('SOLD_OUT', '품절') }));
    expect(html).toMatch(/<button[^>]*>품절<\/button>/);
  });
});

describe('PurchasePanel 모바일 구매 바텀시트', () => {
  it('닫힌 채로 옵션 · 수량 · 담기를 패널과 따로 그린다(라디오 묶음 · 안내 id 가 겹치지 않는다)', () => {
    const html = render();
    const body = sheet(html);
    expect(body).not.toContain(' open=""');
    // 여백은 안쪽 wrapper 에 — dialog 자신을 누른 것만 배경 클릭으로 닫는다
    const dialogTag = body.match(/<dialog[^>]*>/)?.[0] ?? '';
    expect(dialogTag).toMatch(/class="[^"]*\bp-0\b/);
    expect(dialogTag).not.toMatch(/\sp[xytb]-/);
    expect(dialogTag).toContain('overscroll-contain');
    expect(body).toContain('lg:hidden');
    expect(body).toContain('aria-label="옵션 선택 닫기"');
    expect(body).toContain('윤금 드레스');
    expect(body).toContain('사이즈');
    expect(body).toContain('aria-label="수량 늘리기"');
    expect(body).toMatch(/<button[^>]*>장바구니 담기<\/button>/);

    const panelNames = radioNames(html.replace(body, ''));
    const sheetNames = radioNames(body);
    expect(sheetNames.size).toBe(1);
    for (const name of sheetNames) expect(panelNames.has(name)).toBe(false);

    const describedBy = [...html.matchAll(/aria-describedby="([^"]+)"/g)].map((m) => m[1]);
    expect(describedBy).toHaveLength(2);
    expect(new Set(describedBy).size).toBe(2);
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
