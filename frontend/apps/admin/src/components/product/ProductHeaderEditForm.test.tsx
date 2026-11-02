import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { adminProduct } from '@/lib/product/test-fixtures';
import { ProductHeaderEditForm } from './ProductHeaderEditForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

describe('ProductHeaderEditForm', () => {
  it('상세의 값으로 칸을 채우고 바뀐 것이 없으면 저장을 막는다', () => {
    const html = renderToStaticMarkup(
      <ProductHeaderEditForm product={adminProduct({ name: '쪽빛 저고리', visible: true })} />,
    );
    expect(html).toContain('value="쪽빛 저고리"');
    expect(html).toContain('value="가볍게 입는 저고리"');
    expect(html).toContain('&lt;p&gt;소재 &lt;strong&gt;면&lt;/strong&gt;&lt;/p&gt;');
    expect(html).toMatch(/<input(?=[^>]*type="checkbox")(?=[^>]*checked="")[^>]*>/);
    expect(html).toMatch(/<button(?=[^>]*type="submit")(?=[^>]*disabled="")[^>]*>저장<\/button>/);
    expect(html).toContain('바뀐 내용이 없습니다.');
  });

  it('카테고리 · 가격 · 옵션 · SKU · 이미지는 이 화면에서 바꾸지 않는다고 알린다', () => {
    const html = renderToStaticMarkup(<ProductHeaderEditForm product={adminProduct()} />);
    expect(html).toContain('카테고리 · 가격 · 옵션 · SKU · 이미지 수정은 준비 중입니다.');
    expect(html).not.toContain('type="file"');
    expect(html).toContain(`href="/products/${adminProduct().productId}"`);
  });
});
