import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AccessNotice } from '@/components/AccessNotice';
import { ProductHeaderEditForm } from '@/components/product/ProductHeaderEditForm';
import { RefreshButton } from '@/components/product/RefreshButton';
import { LOAD_FAILED } from '@/lib/api-errors';
import { loadProductDetail } from '@/lib/product/detail';
import { serverFetch } from '@/lib/server-api';

export const metadata: Metadata = { title: '상품 기본 정보 수정' };

/**
 * 어드민 상품 기본 정보 수정(#29 · BE #9 후속). 상세와 같이 서버에서 읽어 폼에 `version` 과 함께 넘긴다.
 * 없거나 삭제된 상품은 상세와 같은 not-found 다.
 */
export default async function ProductEditPage({ params }: PageProps<'/products/[productId]/edit'>) {
  const { productId } = await params;
  const loaded = await loadProductDetail(productId, serverFetch);

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
  return <ProductHeaderEditForm product={loaded.product} />;
}
