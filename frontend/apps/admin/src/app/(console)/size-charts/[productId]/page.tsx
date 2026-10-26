import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { SizeChartEditor } from '@/components/size-chart/SizeChartEditor';

export const metadata: Metadata = { title: '상품 사이즈표' };

/** 상품 하나의 사이즈표 입력(#218). 숫자가 아닌 id 는 API 가 400 이라 여기서 먼저 not-found 로 보낸다 */
export default async function ProductSizeChartPage({
  params,
}: PageProps<'/size-charts/[productId]'>) {
  const { productId } = await params;
  if (!/^\d{1,19}$/.test(productId)) notFound();
  return <SizeChartEditor productId={productId} />;
}
