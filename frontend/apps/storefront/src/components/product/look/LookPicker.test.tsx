import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { LookProduct } from '@/lib/catalog/look-cart';
import type { SaleStatusCode } from '@/lib/catalog/types';
import { LookPicker } from './LookPicker';

const krw = (amount: number) => ({ amount, currency: 'KRW' as const });
const status = (code: SaleStatusCode, name: string = code) => ({ code, name, description: '' });

const product = (productId: string, overrides: Partial<LookProduct> = {}): LookProduct => ({
  productId,
  name: `구성품 ${productId}`,
  thumbnail: null,
  salePrice: krw(89000),
  saleStatus: status('ON_SALE', '판매중'),
  optionGroups: [
    {
      optionGroupId: 'size',
      name: '사이즈',
      displayOrder: 0,
      values: [{ optionValueId: 's', name: 'S', displayOrder: 0 }],
    },
  ],
  skus: [
    {
      skuId: `${productId}-s`,
      optionValueIds: ['s'],
      additionalPrice: krw(0),
      salePrice: krw(89000),
      saleStatus: status('ON_SALE'),
    },
  ],
  customOrder: false,
  ...overrides,
});

/** renderToStaticMarkup 가 텍스트 사이에 넣는 <!-- --> 를 지운다 */
const render = (products: LookProduct[], currentProductId?: string) =>
  renderToStaticMarkup(
    <LookPicker products={products} currentProductId={currentProductId} />,
  ).replaceAll('<!-- -->', '');

const checkbox = (html: string, index: number) =>
  html.match(/<input[^>]*type="checkbox"[^>]*>/g)?.[index] ?? '';

describe('LookPicker (첫 렌더)', () => {
  it('살 수 있는 구성품은 함께 담기에 넣은 채로 시작하고 옵션을 보인다', () => {
    const html = render([product('1'), product('2')]);
    expect(checkbox(html, 0)).toContain('checked=""');
    expect(checkbox(html, 1)).toContain('checked=""');
    expect(html.match(/<legend/g)).toHaveLength(2);
    expect(html).toContain('구성품 1, 구성품 2 옵션을 선택해 주세요.');
  });

  it('구성품마다 라디오 name 이 달라 같은 옵션 그룹이 한 묶음이 되지 않는다', () => {
    const names = [
      ...render([product('1'), product('2')]).matchAll(/type="radio"[^>]*name="([^"]+)"/g),
    ].map((match) => match[1]);
    expect(names).toHaveLength(2);
    expect(new Set(names).size).toBe(2);
  });

  it('품절 상품은 빼고 막으며, 판매 상태를 글자로 보인다', () => {
    const html = render([product('1', { saleStatus: status('SOLD_OUT', '품절') })]);
    expect(checkbox(html, 0)).toContain('disabled=""');
    expect(checkbox(html, 0)).not.toContain('checked=""');
    expect(html).toContain('89,000원 · 품절');
    expect(html).toContain('함께 담을 상품을 골라 주세요.');
  });

  it('맞춤 제작 상품은 옵션 대신 상품으로 보낸다', () => {
    const html = render([product('1', { customOrder: true })]);
    expect(html).not.toContain('<legend');
    expect(html).toContain('맞춤 제작 상품은 치수를 받아야 해요.');
    expect(html).toContain('href="/products/1"');
  });

  it('옵션 없는 상품만 있으면 바로 담을 수 있는 수를 보인다 — 장바구니 전까지 버튼은 막는다', () => {
    const plain = product('1', {
      optionGroups: [],
      skus: [{ ...product('1').skus[0]!, optionValueIds: [] }],
    });
    const html = render([plain]);
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>함께 담기 \(1\)<\/button>/);
    expect(html).toContain('장바구니 담기는 준비 중입니다.');
  });

  it('지금 보는 상품은 표시하고 자기 자신으로 가는 링크는 두지 않는다', () => {
    const html = render([product('1'), product('2')], '1');
    expect(html).toContain('구성품 1<span class="text-xs text-muted-fg"> · 지금 보는 상품</span>');
    expect(html).not.toContain('href="/products/1"');
    expect(html).toContain('href="/products/2"');
  });
});
