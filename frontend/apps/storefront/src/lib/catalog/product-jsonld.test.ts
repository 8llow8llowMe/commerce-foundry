import { describe, expect, it } from 'vitest';
import { productJsonLd } from './product-jsonld';
import type { ProductDetail } from './types';

const image = (imageId: string) => ({
  imageId,
  url: `https://img.test/${imageId}.jpg`,
  width: null,
  height: null,
  altText: null,
});

const product = (overrides: Partial<ProductDetail> = {}): ProductDetail => ({
  productId: '4611686018427387904',
  name: '윤금 드레스',
  summary: '실크 드레스',
  categoryPath: [],
  thumbnail: image('main'),
  images: [
    { ...image('main'), displayOrder: 0 },
    { ...image('back'), displayOrder: 1 },
  ],
  descriptionHtml: null,
  salePrice: { amount: 429000, currency: 'KRW' },
  listPrice: null,
  discountRate: null,
  saleStatus: { code: 'SOLD_OUT', name: '품절', description: '' },
  maxQuantityPerOrder: null,
  optionGroups: [],
  skus: [],
  customOrder: null,
  ...overrides,
});

describe('productJsonLd', () => {
  it('schema.org Product 로 이름 · 이미지(중복 제거) · 가격 · 재고 상태를 쓴다', () => {
    expect(JSON.parse(productJsonLd(product()))).toEqual({
      '@context': 'https://schema.org',
      '@type': 'Product',
      productID: '4611686018427387904',
      name: '윤금 드레스',
      description: '실크 드레스',
      image: ['https://img.test/main.jpg', 'https://img.test/back.jpg'],
      offers: {
        '@type': 'Offer',
        price: '429000',
        priceCurrency: 'KRW',
        availability: 'https://schema.org/OutOfStock',
      },
    });
  });

  it('요약 · 이미지가 없으면 그 필드를 빼고, USD 는 소수로 쓴다', () => {
    const data = JSON.parse(
      productJsonLd(
        product({
          summary: null,
          thumbnail: null,
          images: [],
          salePrice: { amount: 8950, currency: 'USD' },
        }),
      ),
    );
    expect(data).not.toHaveProperty('description');
    expect(data).not.toHaveProperty('image');
    expect(data.offers.price).toBe('89.50');
  });

  it('운영 데이터의 < 를 이스케이프해 script 태그를 닫지 못하게 한다', () => {
    const json = productJsonLd(product({ name: '</script><script>alert(1)</script>' }));
    expect(json).not.toContain('<');
    expect(JSON.parse(json).name).toBe('</script><script>alert(1)</script>');
  });
});
