import type { Metadata } from 'next';
import { ProductCustomOrderEditForm } from '@/components/product/ProductCustomOrderEditForm';
import { ProductEditGate } from '@/components/product/ProductEditGate';

export const metadata: Metadata = { title: '상품 맞춤 제작 수정' };

/** 어드민 상품 맞춤 제작 설정 수정(#29 · BE #580). 상세를 읽어 폼에 `version` 과 함께 넘긴다. */
export default async function Page({
  params,
}: PageProps<'/products/[productId]/edit/custom-order'>) {
  const { productId } = await params;
  return (
    <ProductEditGate productId={productId}>
      {(product) => <ProductCustomOrderEditForm product={product} />}
    </ProductEditGate>
  );
}
