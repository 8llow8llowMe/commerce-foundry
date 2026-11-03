import { describe, expect, it } from 'vitest';
import { flattenTree } from '@/lib/category/tree';
import type { CategoryNode } from '@/lib/category/types';
import { categoriesFormOf, categoriesRequest, sameCategories } from './categories';
import { CATEGORY_REQUIRED } from './create';
import { adminProduct } from './test-fixtures';

const node = (categoryId: string, children: CategoryNode[] = []) =>
  ({ categoryId, name: `분류${categoryId}`, visible: true, children }) as unknown as CategoryNode;

describe('카테고리 수정', () => {
  it('대표를 뺀 진열 목록으로 채우고, 트리에 없는 연결은 빼고 센다', () => {
    const rows = flattenTree([node('101'), node('103')]);
    expect(categoriesFormOf(adminProduct(), rows)).toEqual({
      form: { categoryId: '', categoryIds: ['101'] },
      missing: 1,
    });
    const all = flattenTree([node('101'), node('102')]);
    expect(categoriesFormOf(adminProduct(), all)).toEqual({
      form: { categoryId: '102', categoryIds: ['101'] },
      missing: 0,
    });
  });

  it('대표 필수, 진열에서 대표 · 중복을 뺀다', () => {
    expect(categoriesRequest({ categoryId: '', categoryIds: [] }, 1)).toEqual({
      ok: false,
      errors: { categoryId: [CATEGORY_REQUIRED] },
    });
    expect(categoriesRequest({ categoryId: '5', categoryIds: ['5', '6', '6'] }, 1)).toEqual({
      ok: true,
      request: { version: 1, categoryId: '5', categoryIds: ['6'] },
    });
  });

  it('순서만 다르면 같다', () => {
    expect(
      sameCategories(
        { categoryId: '1', categoryIds: ['2', '3'] },
        { categoryId: '1', categoryIds: ['3', '2'] },
      ),
    ).toBe(true);
  });
});
