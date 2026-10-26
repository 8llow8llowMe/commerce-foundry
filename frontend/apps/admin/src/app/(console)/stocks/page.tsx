import type { Metadata } from 'next';
import { ProductPicker } from '@/components/product/ProductPicker';

export const metadata: Metadata = { title: '재고' };

export default function StocksPage() {
  return (
    <ProductPicker
      title="재고"
      description="재고를 볼 상품을 고르세요."
      basePath="/stocks"
      // 숨긴 상품은 옵션 · SKU 를 읽을 어드민 상품 상세 API 가 아직 없어 고를 수 없다(고객 상세가 404)
      hiddenNote="숨긴 상품은 아직 고를 수 없습니다 — 옵션 · SKU 를 읽을 어드민 상품 상세가 생기면 함께 볼 수 있습니다."
    />
  );
}
