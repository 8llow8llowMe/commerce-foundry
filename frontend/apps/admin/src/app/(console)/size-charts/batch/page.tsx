import type { Metadata } from 'next';
import { SizeChartBatch } from '@/components/size-chart/SizeChartBatch';

export const metadata: Metadata = { title: '여러 상품 AI 초안 요청' };

/** 여러 상품의 사이즈표 AI 초안을 한 번에 요청(#218 · BE #737). 정적 경로라 `[productId]` 보다 먼저 맞는다 */
export default function SizeChartBatchPage() {
  return <SizeChartBatch />;
}
