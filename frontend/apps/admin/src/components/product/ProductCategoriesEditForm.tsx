'use client';

import { apiFetch } from '@cf/api-client';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useMemo, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { flattenTree } from '@/lib/category/tree';
import type { CategoryNode } from '@/lib/category/types';
import {
  CATEGORIES_FIELDS,
  categoriesFormOf,
  categoriesRequest,
  sameCategories,
  type CategoriesForm,
} from '@/lib/product/categories';
import type { AdminProductDetail } from '@/lib/product/detail';
import { productCategoriesPath } from '@/lib/product/section';
import { useSectionSave } from '@/lib/product/use-section-save';
import { CategoryFields } from './CategoryFields';
import { SectionEditHeader, SectionForm } from './SectionEditFrame';

/**
 * 어드민 상품 카테고리 수정(#29 · BE #535) — 대표 · 진열 카테고리 전체 교체. 트리(숨김 포함)는 서버 컴포넌트가 읽어 넘긴다.
 * 트리에 없는 연결(그사이 삭제)은 폼에서 빼고 알린다 — 그대로 보내면 서버가 `PRODUCT_012` 로 막는다.
 */
export function ProductCategoriesEditForm({
  product: initial,
  categories,
}: {
  product: AdminProductDetail;
  categories: CategoryNode[];
}) {
  const router = useRouter();
  const rows = useMemo(() => flattenTree(categories), [categories]);
  const [product, setProduct] = useState(initial);
  const [form, setForm] = useState<CategoriesForm>(() => categoriesFormOf(initial, rows).form);
  const section = useSectionSave({
    product,
    keepBusyAfterSave: true,
    onSaved: (saved) => router.replace(`/products/${saved.productId}`),
    onReloaded: (latest) => {
      setProduct(latest);
      setForm(categoriesFormOf(latest, rows).form);
    },
    isField: (key) => CATEGORIES_FIELDS.has(key),
  });

  if (section.forbidden) return <AccessNotice denied="forbidden" />;

  const saved = categoriesFormOf(product, rows);

  return (
    <div className="flex max-w-3xl flex-col gap-8">
      <SectionEditHeader
        productId={product.productId}
        productName={product.name}
        title="카테고리 수정"
      >
        대표 카테고리와 함께 진열할 카테고리를 통째로 바꿉니다. 카테고리 안 진열 순서는 바뀌지
        않습니다.
      </SectionEditHeader>
      {saved.missing > 0 && (
        <p role="status" className="rounded-md bg-muted px-4 py-3 text-sm">
          연결된 카테고리 {saved.missing}개를 카테고리 목록에서 찾을 수 없습니다(그사이 삭제).
          저장하면 연결에서 빠집니다.
        </p>
      )}
      {rows.length === 0 ? (
        <p className="text-sm text-muted-fg">
          카테고리가 없습니다.{' '}
          <Link href="/categories" className="text-fg underline underline-offset-4">
            카테고리 화면
          </Link>
          에서 먼저 만들어 주세요.
        </p>
      ) : (
        <SectionForm
          section={section}
          // 빠진 연결이 있으면 같은 선택이어도 저장할 것이 있다
          unchanged={saved.missing === 0 && sameCategories(form, saved.form)}
          cancelHref={`/products/${product.productId}`}
          onSave={() =>
            void section.save(
              (version) => categoriesRequest(form, version),
              (request) =>
                apiFetch<AdminProductDetail>(productCategoriesPath(product.productId), {
                  method: 'PUT',
                  json: request,
                }),
            )
          }
        >
          <CategoryFields
            rows={rows}
            categoryId={form.categoryId}
            categoryIds={form.categoryIds}
            onChange={(patch) => setForm((value) => ({ ...value, ...patch }))}
            errorsOf={section.errorsOf}
          />
        </SectionForm>
      )}
    </div>
  );
}
