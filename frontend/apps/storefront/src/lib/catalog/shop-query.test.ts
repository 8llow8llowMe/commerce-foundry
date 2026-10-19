import { describe, expect, it } from 'vitest';
import { categoryTrail, parseShopQuery, productsApiPath, shopHref } from './shop-query';
import type { CategoryItem } from './types';

const node = (categoryId: string, children: CategoryItem[] = []): CategoryItem => ({
  categoryId,
  name: `c${categoryId}`,
  displayOrder: 0,
  children,
});

describe('parseShopQuery', () => {
  it('카테고리 id 와 정렬을 읽는다', () => {
    expect(parseShopQuery({ category: '4611686018427387904', sort: 'PRICE_ASC' })).toEqual({
      categoryId: '4611686018427387904',
      sort: 'PRICE_ASC',
    });
  });

  it('없거나 잘못된 값은 기본값(전체 · 신상품순)이다', () => {
    expect(parseShopQuery({})).toEqual({ categoryId: null, sort: 'NEWEST' });
    expect(parseShopQuery({ category: 'abc', sort: 'CHEAP' })).toEqual({
      categoryId: null,
      sort: 'NEWEST',
    });
    expect(parseShopQuery({ category: '1'.repeat(20) })).toMatchObject({ categoryId: null });
  });

  it('같은 키가 여러 번이면 첫 값을 쓴다', () => {
    expect(parseShopQuery({ category: ['7', '8'], sort: ['PRICE_DESC', 'NEWEST'] })).toEqual({
      categoryId: '7',
      sort: 'PRICE_DESC',
    });
  });
});

describe('shopHref', () => {
  it('기본값은 쿼리에서 뺀다', () => {
    expect(shopHref({ categoryId: null, sort: 'NEWEST' })).toBe('/shop');
    expect(shopHref({ categoryId: '7', sort: 'NEWEST' })).toBe('/shop?category=7');
    expect(shopHref({ categoryId: '7', sort: 'PRICE_ASC' })).toBe(
      '/shop?category=7&sort=PRICE_ASC',
    );
  });
});

describe('productsApiPath', () => {
  it('BE 파라미터 이름으로 바꾸고 page · size 를 붙인다', () => {
    expect(productsApiPath({ categoryId: '7', sort: 'PRICE_DESC' }, 2)).toBe(
      '/api/v1/products?categoryId=7&sortType=PRICE_DESC&page=2&size=24',
    );
    expect(productsApiPath({ categoryId: null, sort: 'NEWEST' }, 0)).toBe(
      '/api/v1/products?sortType=NEWEST&page=0&size=24',
    );
  });
});

describe('categoryTrail', () => {
  const tree = [node('1', [node('11'), node('12', [node('121')])]), node('2')];

  it('최상위부터 해당 카테고리까지의 경로를 돌려준다', () => {
    expect(categoryTrail(tree, '121').map((c) => c.categoryId)).toEqual(['1', '12', '121']);
    expect(categoryTrail(tree, '2').map((c) => c.categoryId)).toEqual(['2']);
  });

  it('없는 id 나 null 은 빈 배열이다', () => {
    expect(categoryTrail(tree, '999')).toEqual([]);
    expect(categoryTrail(tree, null)).toEqual([]);
  });
});
