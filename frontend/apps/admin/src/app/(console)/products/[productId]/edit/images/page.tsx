import type { Metadata } from 'next';
import { ProductImagesEditForm } from '@/components/product/ProductImagesEditForm';
import { ProductEditGate } from '@/components/product/ProductEditGate';

export const metadata: Metadata = { title: '상품 이미지 수정' };

/** 어드민 상품 정지 이미지 수정(#29 · BE #560). 상세를 읽어 폼에 `version` 과 함께 넘긴다. */
export default async function Page({ params }: PageProps<'/products/[productId]/edit/images'>) {
  const { productId } = await params;
  return (
    <ProductEditGate productId={productId}>
      {(product) => <ProductImagesEditForm product={product} />}
    </ProductEditGate>
  );
}
