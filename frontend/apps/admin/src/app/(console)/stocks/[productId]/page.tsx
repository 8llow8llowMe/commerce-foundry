import type { Metadata } from 'next';
import { ProductStocks } from '@/components/stock/ProductStocks';

export const metadata: Metadata = { title: '재고' };

/** 상품 하나의 SKU 별 재고(#188). 상품 id 는 숫자 문자열이다 — 형식이 틀리면 상품 API 가 404 · 400 으로 답한다 */
export default async function ProductStocksPage({ params }: PageProps<'/stocks/[productId]'>) {
  const { productId } = await params;
  return <ProductStocks productId={productId} />;
}
