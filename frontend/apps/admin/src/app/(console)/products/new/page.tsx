import type { Metadata } from 'next';
import { ProductCreateForm } from '@/components/product/ProductCreateForm';

export const metadata: Metadata = { title: '상품 등록' };

/** 어드민 상품 등록(#29 · BE #9 E1). 등록하면 상세(`/products/[productId]`)로 옮긴다 */
export default function ProductCreatePage() {
  return <ProductCreateForm />;
}
