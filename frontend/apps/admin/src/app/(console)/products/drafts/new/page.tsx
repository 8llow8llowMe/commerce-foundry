import type { Metadata } from 'next';
import { ProductDraftRequestForm } from '@/components/product-draft/ProductDraftRequestForm';

export const metadata: Metadata = { title: 'AI 상품 초안 요청' };

/** AI 상품 초안 요청(#360 · BE #359). 만들면 초안 상세(`/products/drafts/[draftId]`)로 옮긴다 */
export default function ProductDraftRequestPage() {
  return <ProductDraftRequestForm />;
}
