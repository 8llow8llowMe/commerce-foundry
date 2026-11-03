import { notFound } from 'next/navigation';
import type { ReactNode } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED } from '@/lib/api-errors';
import { loadProductDetail, type AdminProductDetail } from '@/lib/product/detail';
import { serverFetch } from '@/lib/server-api';
import { RefreshButton } from './RefreshButton';

/**
 * 상품 섹션 수정 화면의 공통 읽기(#29) — 서버 컴포넌트다. 상세를 읽어 폼에 넘기고(`version` 포함),
 * 없거나 삭제된 상품은 상세와 같은 not-found, 권한 없음은 `AccessNotice`, 실패는 다시 불러오기다.
 */
export async function ProductEditGate({
  productId,
  children,
}: {
  productId: string;
  children: (product: AdminProductDetail) => ReactNode;
}) {
  const loaded = await loadProductDetail(productId, serverFetch);
  if (loaded.kind === 'notFound') notFound();
  if (loaded.kind === 'denied') return <AccessNotice denied={loaded.denied} />;
  if (loaded.kind === 'failed') return <LoadFailed />;
  return children(loaded.product);
}

export function LoadFailed() {
  return (
    <div role="alert" className="flex items-center gap-4">
      <p className="text-sm text-danger">{LOAD_FAILED}</p>
      <RefreshButton>다시 불러오기</RefreshButton>
    </div>
  );
}
