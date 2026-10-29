import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { ProductSkuCosts } from '@/components/sku-cost/ProductSkuCosts';

export const metadata: Metadata = { title: '원가' };

/** 상품 하나의 SKU 원가(#395). 숫자가 아닌 id 는 상품 API 가 400 이라 여기서 먼저 not-found 로 보낸다 */
export default async function ProductSkuCostsPage({ params }: PageProps<'/sku-costs/[productId]'>) {
  const { productId } = await params;
  if (!/^\d{1,19}$/.test(productId)) notFound();
  return <ProductSkuCosts productId={productId} />;
}
