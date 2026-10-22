import type { Metadata } from 'next';
import { ProductList } from '@/components/product/ProductList';
import { parseProductQuery } from '@/lib/product/query';

export const metadata: Metadata = { title: '상품' };

/** 어드민 상품 목록(#208). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function ProductsPage({ searchParams }: PageProps<'/products'>) {
  return <ProductList query={parseProductQuery(await searchParams)} />;
}
