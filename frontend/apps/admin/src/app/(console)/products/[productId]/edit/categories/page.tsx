import type { Metadata } from 'next';
import { ProductCategoriesEditForm } from '@/components/product/ProductCategoriesEditForm';
import { LoadFailed, ProductEditGate } from '@/components/product/ProductEditGate';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import { serverFetch } from '@/lib/server-api';

export const metadata: Metadata = { title: '상품 카테고리 수정' };

/**
 * 어드민 상품 카테고리 수정(#29 · BE #535). 상세와 카테고리 트리(숨김 포함)를 함께 읽는다.
 * 트리를 못 읽으면 고를 수 없으니 다시 불러오기를 낸다(권한 없음은 상세 쪽이 먼저 알린다).
 */
export default async function Page({ params }: PageProps<'/products/[productId]/edit/categories'>) {
  const { productId } = await params;
  const categories = await serverFetch<CategoryTree>(CATEGORIES_PATH).then(
    (tree) => tree.categories,
    () => null,
  );
  return (
    <ProductEditGate productId={productId}>
      {(product) =>
        categories === null ? (
          <LoadFailed />
        ) : (
          <ProductCategoriesEditForm product={product} categories={categories} />
        )
      }
    </ProductEditGate>
  );
}
