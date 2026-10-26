import type { Metadata } from 'next';
import { ProductPicker } from '@/components/product/ProductPicker';

export const metadata: Metadata = { title: '상품 사이즈표' };

/** 사이즈표를 넣을 상품 고르기(#218). 어드민 사이즈표 API 는 숨긴 상품도 받는다 */
export default function SizeChartsPage() {
  return (
    <ProductPicker
      title="상품 사이즈표"
      description="사이즈표를 입력할 상품을 고르세요."
      basePath="/size-charts"
    />
  );
}
