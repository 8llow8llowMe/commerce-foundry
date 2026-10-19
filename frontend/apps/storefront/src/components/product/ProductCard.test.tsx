import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import type { ProductListItem } from '@/lib/catalog/types';
import { LocaleProvider } from '@/components/i18n/LocaleProvider';
import { en } from '@/lib/i18n/dictionaries/en';
import { ProductCard } from './ProductCard';

const product = (overrides: Partial<ProductListItem> = {}): ProductListItem => ({
  productId: '4611686018427387904',
  name: '윤금 드레스',
  thumbnail: {
    imageId: '1',
    url: 'https://img.test/1.jpg',
    width: 900,
    height: 1200,
    altText: '정면 착용컷',
  },
  salePrice: { amount: 129000, currency: 'KRW' },
  listPrice: null,
  discountRate: null,
  hasOptionSurcharge: false,
  saleStatus: { code: 'ON_SALE', name: '판매중', description: '구매할 수 있습니다.' },
  customOrder: false,
  ...overrides,
});

const render = (overrides?: Partial<ProductListItem>) =>
  renderToStaticMarkup(<ProductCard product={product(overrides)} />);

describe('ProductCard', () => {
  it('상세로 가는 링크에 이미지 · 이름 · 서버 가격을 보여 준다', () => {
    const html = render();
    expect(html).toContain('href="/products/4611686018427387904"');
    expect(html).toContain('src="https://img.test/1.jpg"');
    expect(html).toContain('alt="정면 착용컷"');
    expect(html).toContain('윤금 드레스');
    expect(html).toContain('129,000원');
    expect(html).not.toContain('<del');
    expect(html).not.toContain('~');
  });

  it('alt 가 없으면 상품명을 쓰고, 이미지가 없으면 자리 표시를 한다', () => {
    expect(render({ thumbnail: { ...product().thumbnail!, altText: null } })).toContain(
      'alt="윤금 드레스"',
    );
    const html = render({ thumbnail: null });
    expect(html).not.toContain('<img');
    expect(html).toContain('이미지 준비 중');
  });

  it('할인이면 할인율 · 정가를, 옵션 추가금이 있으면 ~ 를 붙인다', () => {
    const html = render({
      listPrice: { amount: 159000, currency: 'KRW' },
      discountRate: 18,
      hasOptionSurcharge: true,
    });
    expect(html).toContain('18%');
    expect(html).toMatch(/<del[^>]*>.*159,000원<\/del>/);
    expect(html).toContain('129,000원<span aria-hidden="true">~</span>');
  });

  it('판매중이 아니면 서버의 상태 문구를 배지로 보여 준다', () => {
    const html = render({
      saleStatus: { code: 'SOLD_OUT', name: '품절', description: '재고가 모두 소진되었습니다.' },
    });
    expect(html).toContain('>품절</span>');
    expect(render()).not.toContain('판매중');
  });

  it('영문 스토어는 /en 링크 · 통화 기호 금액 · 영문 스크린리더 문구다', () => {
    const html = renderToStaticMarkup(
      <LocaleProvider locale="en" prefix="/en" dict={en}>
        <ProductCard
          product={product({ discountRate: 18, listPrice: { amount: 159000, currency: 'KRW' } })}
        />
      </LocaleProvider>,
    ).replaceAll('<!-- -->', '');
    expect(html).toMatch(/href="\/en\/products\/[^"]+"/);
    expect(html).toContain('₩');
    expect(html).not.toContain('원');
    expect(html).toContain('<span class="sr-only">Discount </span>');
  });
});
