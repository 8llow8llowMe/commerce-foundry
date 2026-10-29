import { describe, expect, it } from 'vitest';
import { normalizeProductLooks } from './product-looks';

const image = { url: 'https://cdn.example.com/a.jpg', width: 1200, height: 1600, altText: '코디' };

describe('normalizeProductLooks', () => {
  it('계약 모양은 그대로 둔다 — 상품별 룩에는 설명이 없어 null 이다', () => {
    const products = [{ productId: '1', name: '저고리' }];
    expect(
      normalizeProductLooks({ looks: [{ lookId: '10', title: '가을', image, products }] }),
    ).toEqual([{ lookId: '10', title: '가을', description: null, image, products }]);
  });

  it('looks 가 배열이 아니면 빈 목록', () => {
    expect(normalizeProductLooks(null)).toEqual([]);
    expect(normalizeProductLooks({})).toEqual([]);
    expect(normalizeProductLooks({ looks: {} })).toEqual([]);
    expect(normalizeProductLooks([])).toEqual([]);
  });

  it('아이디 · 제목이 없거나 같은 아이디가 다시 나온 룩은 뺀다', () => {
    const looks = normalizeProductLooks({
      looks: [
        { lookId: '1', title: '첫 룩', image, products: [] },
        { lookId: '1', title: '중복', image, products: [] },
        { lookId: '2', title: '  ' },
        { title: '아이디 없음' },
        42,
      ],
    });
    expect(looks.map((look) => look.title)).toEqual(['첫 룩']);
  });

  it('products 가 없거나 배열이 아니면 빈 배열, 아이디 없는 카드와 중복 카드는 뺀다', () => {
    const [noProducts, broken] = normalizeProductLooks({
      looks: [
        { lookId: '1', title: 'a', image, items: [{ productId: '9' }] },
        {
          lookId: '2',
          title: 'b',
          image,
          products: [
            { productId: '1' },
            null,
            { name: '아이디 없음' },
            { productId: '1' },
            { productId: '2' },
          ],
        },
      ],
    });
    expect(noProducts?.products).toEqual([]);
    expect(broken?.products.map((product) => product.productId)).toEqual(['1', '2']);
  });

  it('이미지 주소가 없으면 null, 크기 · 대체 텍스트가 이상하면 null 로 둔다', () => {
    const [noUrl, odd] = normalizeProductLooks({
      looks: [
        { lookId: '1', title: 'a', image: { width: 10 }, products: [] },
        {
          lookId: '2',
          title: 'b',
          description: '첫 줄\n둘째 줄',
          image: { url: 'https://cdn.example.com/b.jpg', width: '1200', height: -1, altText: ' ' },
          products: [],
        },
      ],
    });
    expect(noUrl?.image).toBeNull();
    expect(odd?.image).toEqual({
      url: 'https://cdn.example.com/b.jpg',
      width: null,
      height: null,
      altText: null,
    });
    expect(odd?.description).toBe('첫 줄\n둘째 줄');
  });
});
