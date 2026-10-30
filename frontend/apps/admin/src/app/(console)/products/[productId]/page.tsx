import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AccessNotice } from '@/components/AccessNotice';
import { ProductDetailView } from '@/components/product/ProductDetailView';
import { RefreshButton } from '@/components/product/RefreshButton';
import { LOAD_FAILED } from '@/lib/api-errors';
import { CATEGORIES_PATH, type CategoryTree } from '@/lib/category/types';
import { loadProductDetail } from '@/lib/product/detail';
import { serverFetch } from '@/lib/server-api';

export const metadata: Metadata = { title: '상품 상세' };

/**
 * 어드민 상품 상세(읽기 전용, #418). 숨긴 상품도 보인다. 수정은 BE #9 의 수정 API 뒤에 이 화면에 얹는다.
 * 서버 컴포넌트에서 읽는다 — 상세 설명 HTML 을 서버에서 정제해 넣어야 해서다(`frontend/docs/conventions.md` §2).
 */
export default async function ProductDetailPage({ params }: PageProps<'/products/[productId]'>) {
  const { productId } = await params;
  const [loaded, categories] = await Promise.all([
    loadProductDetail(productId, serverFetch),
    // 카테고리 이름은 곁가지다 — 못 읽어도 상세는 보인다(화면이 안내한다)
    serverFetch<CategoryTree>(CATEGORIES_PATH).then(
      (tree) => tree.categories,
      () => null,
    ),
  ]);

  if (loaded.kind === 'notFound') notFound();
  if (loaded.kind === 'denied') return <AccessNotice denied={loaded.denied} />;
  if (loaded.kind === 'failed') {
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <RefreshButton>다시 불러오기</RefreshButton>
      </div>
    );
  }
  return <ProductDetailView product={loaded.product} categories={categories} />;
}
