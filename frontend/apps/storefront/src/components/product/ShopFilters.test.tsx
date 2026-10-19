import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ShopQuery } from '@/lib/catalog/shop-query';
import type { CategoryItem } from '@/lib/catalog/types';
import { ShopFilters } from './ShopFilters';

const CATEGORIES: CategoryItem[] = [
  {
    categoryId: '1',
    name: '원피스',
    displayOrder: 0,
    children: [
      { categoryId: '11', name: '롱', displayOrder: 0, children: [] },
      { categoryId: '12', name: '미디', displayOrder: 1, children: [] },
    ],
  },
  { categoryId: '2', name: '스커트', displayOrder: 1, children: [] },
];

const render = (query: ShopQuery) =>
  renderToStaticMarkup(<ShopFilters categories={CATEGORIES} query={query} />);

/** aria-current 가 붙은 링크의 텍스트들 */
const currentLabels = (html: string) =>
  [...html.matchAll(/<a [^>]*aria-current="[^"]+"[^>]*>([^<]+)<\/a>/g)].map((m) => m[1]);

describe('ShopFilters', () => {
  it('전체 · 신상품순이 기본으로 선택되고 하위 줄은 없다', () => {
    const html = render({ categoryId: null, sort: 'NEWEST' });
    expect(currentLabels(html)).toEqual(['전체', '신상품순']);
    expect(html).not.toContain('원피스 전체');
  });

  it('하위 카테고리를 고르면 최상위와 그 하위가 함께 선택되고 형제들이 보인다', () => {
    const html = render({ categoryId: '12', sort: 'PRICE_ASC' });
    expect(currentLabels(html)).toEqual(['원피스', '미디', '낮은 가격순']);
    expect(html).toContain('원피스 전체');
    expect(html).toContain('>롱</a>');
  });

  it('카테고리 링크는 정렬을, 정렬 링크는 카테고리를 유지한다', () => {
    const html = render({ categoryId: '12', sort: 'PRICE_ASC' });
    expect(html).toContain('href="/shop?category=2&amp;sort=PRICE_ASC"');
    expect(html).toContain('href="/shop?category=12&amp;sort=PRICE_DESC"');
    expect(html).toContain('href="/shop?category=12"');
  });

  it('카테고리를 못 받았으면 카테고리 줄 없이 정렬만 보여 준다', () => {
    const html = renderToStaticMarkup(
      <ShopFilters categories={[]} query={{ categoryId: null, sort: 'NEWEST' }} />,
    );
    expect(html).not.toContain('aria-label="카테고리"');
    expect(html).toContain('aria-label="정렬"');
  });
});
