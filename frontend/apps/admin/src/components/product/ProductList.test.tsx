import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { parseProductQuery } from '@/lib/product/query';
import { helpTopicFor } from '@/lib/tour/tours';
import { ProductList } from './ProductList';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

describe('ProductList', () => {
  it('불러오기 전에도 상품 목록 투어(#228 products-v1)의 필수 앵커(등록 · 조건)를 정의 순서로 단다', () => {
    const html = renderToStaticMarkup(<ProductList query={parseProductQuery({})} />);
    const anchors = [...html.matchAll(/data-tour="([^"]+)"/g)].map((match) => match[1]);
    const required = helpTopicFor('/products')
      .tour?.steps.filter((step) => !step.optional)
      .map((step) => step.target);
    expect(anchors).toEqual(required);
  });
});
