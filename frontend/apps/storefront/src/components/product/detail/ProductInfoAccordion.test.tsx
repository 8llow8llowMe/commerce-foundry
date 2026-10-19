import type { ProductInfo } from '@cf/ui';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { ProductInfoAccordion } from './ProductInfoAccordion';

// 실제 상점 값이 아닌 자리 값
const FULL: ProductInfo = {
  sizeGuide: {
    columns: ['사이즈', '가슴둘레', '총장'],
    rows: [
      ['S', '84', '110'],
      ['M', '88', '112'],
    ],
    note: '단위 cm',
  },
  shipping: ['첫 단락', '둘째 단락'],
};

const render = (info: ProductInfo, labels = ko.product.info) =>
  renderToStaticMarkup(<ProductInfoAccordion info={info} labels={labels} />);

describe('ProductInfoAccordion', () => {
  it('값이 모두 없으면 아무것도 그리지 않는다(고객사 값 받기 전)', () => {
    expect(render({ sizeGuide: null, shipping: null })).toBe('');
  });

  it('스크립트 없이 열고 닫는 details 로, 닫힌 채 그린다', () => {
    const html = render(FULL);
    expect(html.match(/<details class="group">/g)).toHaveLength(2);
    expect(html).not.toContain(' open');
    expect(html).toMatch(/<summary[^>]*>사이즈 가이드<span aria-hidden="true"/);
    expect(html).toMatch(/<summary[^>]*>배송 · 교환<span aria-hidden="true"/);
  });

  it('사이즈 가이드는 머리줄 · 줄 머리가 있는 진짜 표다', () => {
    const html = render(FULL);
    expect(html).toContain(
      '<th scope="col" class="border-b border-border py-2 pr-4 font-normal text-muted-fg">가슴둘레</th>',
    );
    expect(html).toMatch(/<th scope="row"[^>]*>M<\/th><td[^>]*>88<\/td><td[^>]*>112<\/td>/);
    expect(html).toContain('단위 cm');
  });

  it('값이 있는 항목만 그린다', () => {
    const html = render({ sizeGuide: null, shipping: ['안내'] });
    expect(html).not.toContain('사이즈 가이드');
    expect(html).toContain('<p>안내</p>');
  });

  it('영문 사전이면 제목이 영문이다', () => {
    const html = render(FULL, en.product.info);
    expect(html).toContain('Size guide');
    expect(html).toContain('Shipping &amp; returns');
  });
});
