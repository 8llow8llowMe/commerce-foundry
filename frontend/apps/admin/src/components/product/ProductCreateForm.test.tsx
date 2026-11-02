import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ProductCreateForm } from './ProductCreateForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

describe('ProductCreateForm', () => {
  it('온보딩 투어 앵커(#228 product-new-v1)를 단다', () => {
    const html = renderToStaticMarkup(<ProductCreateForm />);
    for (const anchor of [
      'product-basic',
      'product-price',
      'product-options',
      'product-skus',
      'product-submit',
    ]) {
      expect(html).toContain(`data-tour="${anchor}"`);
    }
  });

  it('이미지 업로드 칸은 첨부 API(BE #9 F2) 전까지 꺼 두고 이유를 안내한다', () => {
    const html = renderToStaticMarkup(<ProductCreateForm />);
    expect(html).toMatch(/<input(?=[^>]*type="file")[^>]*disabled=""/);
    expect(html).toContain('상품에 이미지를 붙이는 서버 기능이 준비되면 열립니다.');
  });
});
