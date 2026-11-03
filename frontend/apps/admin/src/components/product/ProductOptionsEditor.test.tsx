import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { adminProduct } from '@/lib/product/test-fixtures';
import { ADD_NO_OPTIONS } from '@/lib/product/options';
import { ProductOptionsEditor } from './ProductOptionsEditor';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

describe('ProductOptionsEditor(#29)', () => {
  it('옵션 이름 · SKU · 조합 추가 · 은퇴 폼을 따로 그린다', () => {
    const html = renderToStaticMarkup(<ProductOptionsEditor product={adminProduct()} />);
    expect(html).toContain('옵션 이름 · 순서');
    expect(html).toContain('SKU 2개');
    expect(html).toContain('옵션 값 · 조합 추가');
    expect(html).toContain('옵션 은퇴');
    expect(html).toContain('색상: 쪽빛 · 사이즈: L');
    expect(html).toContain('(은퇴 옵션)');
    // 은퇴 옵션 SKU 는 판매중으로 돌릴 수 없다
    expect(html).toMatch(/<option value="ON_SALE" disabled="">판매중<\/option>/);
    // 은퇴는 확인 전에 누를 수 없다
    expect(html).toMatch(/<button(?=[^>]*type="submit")(?=[^>]*disabled="")[^>]*>은퇴<\/button>/);
  });

  it('옵션 없는 상품은 조합 추가를 막고 은퇴 폼이 없다', () => {
    const html = renderToStaticMarkup(
      <ProductOptionsEditor product={adminProduct({ optionGroups: [], skus: [] })} />,
    );
    expect(html).toContain(ADD_NO_OPTIONS);
    expect(html).not.toContain('옵션 은퇴');
  });
});
