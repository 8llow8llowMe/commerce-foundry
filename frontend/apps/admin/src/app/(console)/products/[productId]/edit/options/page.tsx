import type { Metadata } from 'next';
import { ProductOptionsEditor } from '@/components/product/ProductOptionsEditor';
import { ProductEditGate } from '@/components/product/ProductEditGate';

export const metadata: Metadata = { title: '상품 옵션 · SKU 수정' };

/** 어드민 상품 옵션 · SKU 수정(#29 · BE #573 · #576 · #583 · #585). 상세를 읽어 폼들에 넘긴다. */
export default async function Page({ params }: PageProps<'/products/[productId]/edit/options'>) {
  const { productId } = await params;
  return (
    <ProductEditGate productId={productId}>
      {(product) => <ProductOptionsEditor product={product} />}
    </ProductEditGate>
  );
}
