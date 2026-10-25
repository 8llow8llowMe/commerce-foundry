import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ProductListItem } from '@/lib/catalog/types';
import { ProductGrid } from './ProductGrid';

const item = (productId: string): ProductListItem => ({
  productId,
  name: `상품 ${productId}`,
  thumbnail: null,
  salePrice: { amount: 1000, currency: 'KRW' },
  listPrice: null,
  discountRate: null,
  hasOptionSurcharge: false,
  saleStatus: { code: 'ON_SALE', name: '판매중', description: '' },
  customOrder: false,
  preorder: false,
});

const QUERY = { categoryId: null, sort: 'NEWEST' } as const;

describe('ProductGrid (서버 렌더 초기 상태)', () => {
  it('첫 페이지 상품을 그리고, 다음이 있으면 더 보기 버튼을 둔다', () => {
    const html = renderToStaticMarkup(
      <ProductGrid query={QUERY} initial={{ contents: [item('1'), item('2')], hasNext: true }} />,
    );
    expect(html.match(/href="\/products\//g)).toHaveLength(2);
    expect(html).toContain('더 보기');
    expect(html).toContain('role="status"');
  });

  it('마지막 페이지면 더 보기 버튼이 없다', () => {
    const html = renderToStaticMarkup(
      <ProductGrid query={QUERY} initial={{ contents: [item('1')], hasNext: false }} />,
    );
    expect(html).not.toContain('더 보기');
  });

  it('상품이 없으면 빈 안내를 보여 준다', () => {
    const html = renderToStaticMarkup(
      <ProductGrid query={QUERY} initial={{ contents: [], hasNext: false }} />,
    );
    expect(html).toContain('아직 등록된 상품이 없습니다.');
    expect(html).not.toContain('<ul');
  });
});
