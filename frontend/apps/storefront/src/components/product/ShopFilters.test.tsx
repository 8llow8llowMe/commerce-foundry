import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ShopQuery } from '@/lib/catalog/shop-query';
import type { CategoryItem } from '@/lib/catalog/types';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
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
  renderToStaticMarkup(<ShopFilters categories={CATEGORIES} query={query} labels={ko.shop} />);

/** aria-current 가 붙은 링크의 텍스트들 */
const currentLabels = (html: string) =>
  [...html.matchAll(/<a [^>]*aria-current="[^"]+"[^>]*>([^<]+)<\/a>/g)].map((m) => m[1]);

/** 화면에 놓인 줄(PC · 모바일 최상위 줄)과 모바일 바텀시트를 나눈다 */
const split = (html: string) => {
  const at = html.indexOf('id="shop-sort-filter"');
  return { inline: html.slice(0, at), sheet: html.slice(at) };
};

describe('ShopFilters', () => {
  it('전체 · 신상품순이 기본으로 선택되고 하위 줄은 없다', () => {
    const html = render({ categoryId: null, sort: 'NEWEST' });
    expect(currentLabels(split(html).inline)).toEqual(['전체', '신상품순']);
    expect(html).not.toContain('원피스 전체');
  });

  it('하위 카테고리를 고르면 최상위와 그 하위가 함께 선택되고 형제들이 보인다', () => {
    const html = render({ categoryId: '12', sort: 'PRICE_ASC' });
    expect(currentLabels(split(html).inline)).toEqual(['원피스', '미디', '낮은 가격순']);
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
      <ShopFilters categories={[]} query={{ categoryId: null, sort: 'NEWEST' }} labels={ko.shop} />,
    );
    expect(html).not.toContain('aria-label="카테고리"');
    expect(html).toContain('aria-label="정렬"');
  });

  it('영문 사전이면 카테고리 · 정렬 라벨이 영문이다(카테고리 이름은 운영 데이터 그대로)', () => {
    const html = renderToStaticMarkup(
      <ShopFilters
        categories={CATEGORIES}
        query={{ categoryId: '1', sort: 'PRICE_ASC' }}
        labels={en.shop}
      />,
    );
    expect(currentLabels(split(html).inline)).toEqual([
      '원피스',
      'All 원피스',
      'Price: low to high',
    ]);
    expect(html).toContain('aria-label="Sort"');
  });

  it('모바일은 하단 고정 버튼으로 정렬 · 세부 카테고리 바텀시트를 연다(스크립트 없는 popover)', () => {
    const html = render({ categoryId: '12', sort: 'PRICE_ASC' });
    const { inline, sheet } = split(html);
    expect(inline).toMatch(
      /<button type="button" popoverTarget="shop-sort-filter"[^>]*>정렬 · 필터<\/button>/i,
    );
    // PC 줄의 정렬 · 세부 카테고리는 모바일에서 숨는다
    expect(inline).toContain('class="hidden lg:ml-auto lg:block"');
    expect(inline).toContain('class="hidden gap-5 lg:flex"');

    expect(sheet).toMatch(/^id="shop-sort-filter" popover="auto" role="dialog"/);
    expect(currentLabels(sheet)).toEqual(['낮은 가격순', '미디']);
    expect(sheet).toMatch(/popoverTargetAction="hide" aria-label="닫기"/i);
  });

  it('하위 카테고리가 없으면 시트에는 정렬만 있다', () => {
    const { sheet } = split(render({ categoryId: '2', sort: 'NEWEST' }));
    expect(sheet).not.toContain('aria-label="카테고리"');
    expect(currentLabels(sheet)).toEqual(['신상품순']);
  });
});
