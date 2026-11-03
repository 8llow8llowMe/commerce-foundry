import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import type { CategoryNode } from '@/lib/category/types';
import { adminProduct } from '@/lib/product/test-fixtures';
import { ProductCategoriesEditForm } from './ProductCategoriesEditForm';
import { ProductCustomOrderEditForm } from './ProductCustomOrderEditForm';
import { ProductImagesEditForm } from './ProductImagesEditForm';
import { ProductPricingEditForm } from './ProductPricingEditForm';

vi.mock('next/navigation', () => ({ useRouter: () => ({ replace: vi.fn() }) }));

const saveDisabled = /<button(?=[^>]*type="submit")(?=[^>]*disabled="")[^>]*>저장<\/button>/;

describe('섹션 수정 화면(#29)', () => {
  it('가격 — 서버 금액을 입력 칸에 채우고 바뀐 것이 없으면 저장을 막는다', () => {
    const html = renderToStaticMarkup(<ProductPricingEditForm product={adminProduct()} />);
    expect(html).toContain('value="129000"');
    expect(html).toContain('value="159000"');
    expect(html).toContain('지금 판매가 129,000원');
    expect(html).toMatch(saveDisabled);
    expect(html).toContain(`href="/products/${adminProduct().productId}"`);
  });

  it('카테고리 — 트리에 없는 연결을 알리고 저장할 수 있게 둔다', () => {
    const categories = [
      { categoryId: '101', name: '상의', visible: true, children: [] },
    ] as unknown as CategoryNode[];
    const html = renderToStaticMarkup(
      <ProductCategoriesEditForm product={adminProduct()} categories={categories} />,
    );
    expect(html).toContain('연결된 카테고리 1개를 카테고리 목록에서 찾을 수 없습니다');
    expect(html).not.toMatch(saveDisabled);
  });

  it('이미지 — 기존 대표 이미지를 보이고 영상은 그대로 남는다고 알린다', () => {
    const html = renderToStaticMarkup(<ProductImagesEditForm product={adminProduct()} />);
    expect(html).toContain('src="https://img.test/thumb.jpg"');
    expect(html).toContain('영상 1개는 이 화면에서 바꾸지 않고 그대로 남습니다.');
    expect(html).toContain('value="정면"');
    expect(html).toMatch(saveDisabled);
  });

  it('맞춤 제작 — 저장한 항목의 키는 읽기 전용이다', () => {
    const html = renderToStaticMarkup(
      <ProductCustomOrderEditForm
        product={adminProduct({
          customOrderFields: [
            {
              fieldKey: 'chest',
              label: '가슴둘레',
              inputType: { code: 'NUMBER', name: '숫자', description: '' },
              unit: 'cm',
              required: true,
              minValue: null,
              maxValue: null,
              stepValue: null,
              helpText: null,
              displayOrder: 0,
            },
          ],
        })}
      />,
    );
    expect(html).toMatch(/<input(?=[^>]*value="chest")(?=[^>]*readOnly="")[^>]*>/);
    expect(html).toContain('입력 항목 1개');
    expect(html).toMatch(saveDisabled);
  });
});
