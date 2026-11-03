import type { Metadata } from 'next';
import { ProductPricingEditForm } from '@/components/product/ProductPricingEditForm';
import { ProductEditGate } from '@/components/product/ProductEditGate';

export const metadata: Metadata = { title: '상품 가격 수정' };

/** 어드민 상품 가격 수정(#29 · BE #553). 상세를 읽어 폼에 `version` 과 함께 넘긴다. */
export default async function Page({ params }: PageProps<'/products/[productId]/edit/pricing'>) {
  const { productId } = await params;
  return (
    <ProductEditGate productId={productId}>
      {(product) => <ProductPricingEditForm product={product} />}
    </ProductEditGate>
  );
}
