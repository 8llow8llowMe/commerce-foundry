import type { FormErrors } from '@/lib/api-errors';
import type { CategoryRow } from '@/lib/category/tree';
import { CATEGORY_IDS_MAX, CATEGORY_IDS_TOO_MANY, CATEGORY_REQUIRED } from './create';
import type { AdminProductDetail } from './detail';
import { addError } from './section';

// 어드민 상품 카테고리 수정(#29 · BE #535) — `PUT /api/v1/admin/products/{productId}/categories`.
// 대표 · 진열 카테고리를 전체 교체한다. `categoryIds` 는 대표를 빼고 보내도 서버가 대표를 더한다(최대 20개, 빈 배열 허용).
// 같은 요청이어도 저장하면 버전이 오른다. 없는 카테고리는 400 `PRODUCT_012`, 카테고리 트리 잠금 대기 초과는 409 `PRODUCT_045`.

export type CategoriesForm = {
  /** 대표. 트리에 없는 대표(그사이 삭제)는 '' 로 두고 다시 고르게 한다 */
  categoryId: string;
  /** 진열(대표 제외) */
  categoryIds: string[];
};

export type CategoriesRequest = { version: number; categoryId: string; categoryIds: string[] };

/**
 * 상세 → 폼. 트리에 없는 아이디는 뺀다 — 그대로 보내면 `PRODUCT_012` 다. 뺀 개수는 화면이 알린다(`missing`).
 */
export function categoriesFormOf(
  product: AdminProductDetail,
  rows: readonly CategoryRow[],
): { form: CategoriesForm; missing: number } {
  const known = new Set(rows.map((row) => row.node.categoryId));
  const linked = [
    product.categoryId,
    ...product.categoryIds.filter((id) => id !== product.categoryId),
  ];
  const missing = linked.filter((id) => !known.has(id)).length;
  return {
    form: {
      categoryId: known.has(product.categoryId) ? product.categoryId : '',
      categoryIds: product.categoryIds.filter((id) => id !== product.categoryId && known.has(id)),
    },
    missing,
  };
}

export function sameCategories(a: CategoriesForm, b: CategoriesForm): boolean {
  const set = (form: CategoriesForm) =>
    [...new Set(form.categoryIds.filter((id) => id !== form.categoryId))].sort().join(',');
  return a.categoryId === b.categoryId && set(a) === set(b);
}

export const CATEGORIES_FIELDS = new Set(['categoryId', 'categoryIds']);

export type BuildCategories =
  { ok: true; request: CategoriesRequest } | { ok: false; errors: FormErrors };

export function categoriesRequest(form: CategoriesForm, version: number): BuildCategories {
  const errors: FormErrors = {};
  if (form.categoryId === '') addError(errors, 'categoryId', CATEGORY_REQUIRED);
  const categoryIds = [
    ...new Set(form.categoryIds.filter((id) => id !== '' && id !== form.categoryId)),
  ];
  if (categoryIds.length > CATEGORY_IDS_MAX) addError(errors, 'categoryIds', CATEGORY_IDS_TOO_MANY);
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { version, categoryId: form.categoryId, categoryIds } };
}
