import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  hasFilter,
  parseProductQuery,
  productsApiPath,
  productsHref,
  type ProductQuery,
} from './query';

const FULL: ProductQuery = {
  keyword: '저고리',
  categoryId: '101',
  saleStatus: 'STOP_SELLING',
  visible: false,
  availability: 'SOLD_OUT',
  sortType: 'NAME_ASC',
  page: 2,
};

describe('parseProductQuery', () => {
  it('빈 주소는 기본 조건이다', () => {
    expect(parseProductQuery({})).toEqual(DEFAULT_QUERY);
  });

  it('허용 값은 그대로 읽는다', () => {
    expect(
      parseProductQuery({
        keyword: ' 저고리 ',
        categoryId: '101',
        saleStatus: 'STOP_SELLING',
        visible: 'false',
        availability: 'SOLD_OUT',
        sortType: 'NAME_ASC',
        page: '2',
      }),
    ).toEqual(FULL);
  });

  it('허용하지 않는 값은 전체(기본값)로 본다', () => {
    expect(
      parseProductQuery({
        categoryId: '0',
        saleStatus: 'SOLD_OUT',
        visible: 'yes',
        availability: 'on_sale',
        sortType: 'PRICE',
        page: '-1',
      }),
    ).toEqual(DEFAULT_QUERY);
    expect(parseProductQuery({ categoryId: '1e3', page: '1.5' })).toEqual(DEFAULT_QUERY);
    // Long 최대값까지만
    expect(parseProductQuery({ categoryId: '9223372036854775807' }).categoryId).toBe(
      '9223372036854775807',
    );
    expect(parseProductQuery({ categoryId: '9999999999999999999' }).categoryId).toBeNull();
  });

  it('검색어는 100자에서 자르고, 여러 값이면 첫 값을 쓴다', () => {
    expect(parseProductQuery({ keyword: 'a'.repeat(150) }).keyword).toHaveLength(100);
    expect(parseProductQuery({ keyword: `${'a'.repeat(99)} b` }).keyword).toBe('a'.repeat(99));
    expect(parseProductQuery({ keyword: ['가', '나'], visible: ['true', 'false'] })).toMatchObject({
      keyword: '가',
      visible: true,
    });
  });
});

describe('productsHref', () => {
  it('기본값은 주소에서 뺀다', () => {
    expect(productsHref(DEFAULT_QUERY)).toBe('/products');
  });

  it('조건을 주소로 쓰고, 다시 읽으면 같은 조건이다', () => {
    const href = productsHref(FULL);
    const params = Object.fromEntries(new URL(href, 'http://admin.test').searchParams);
    expect(parseProductQuery(params)).toEqual(FULL);
  });
});

describe('productsApiPath', () => {
  it('정렬 · 페이지 · 크기는 늘 보내고, 전체(null)인 조건은 보내지 않는다', () => {
    expect(productsApiPath(DEFAULT_QUERY)).toBe(
      '/api/v1/admin/products?sortType=NEWEST&page=0&size=20',
    );
  });

  it('조건을 서버 파라미터 이름 그대로 보낸다', () => {
    const params = new URL(productsApiPath(FULL), 'http://admin.test').searchParams;
    expect(Object.fromEntries(params)).toEqual({
      keyword: '저고리',
      categoryId: '101',
      saleStatus: 'STOP_SELLING',
      visible: 'false',
      availability: 'SOLD_OUT',
      sortType: 'NAME_ASC',
      page: '2',
      size: '20',
    });
  });
});

describe('hasFilter', () => {
  it('정렬 · 페이지만 다르면 필터가 없다', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, sortType: 'NAME_ASC', page: 3 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, visible: true })).toBe(true);
  });
});
