import type { Metadata } from 'next';
import { ProductDeletedNotice } from '@/components/product/ProductDeletedNotice';
import { ProductList } from '@/components/product/ProductList';
import { DELETED_PARAM, parseDeletedResult } from '@/lib/product/delete';
import { parseProductQuery } from '@/lib/product/query';

export const metadata: Metadata = { title: '상품' };

/**
 * 어드민 상품 목록(#208). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다.
 * 상세에서 삭제하고 오면(`?deleted=`, #29) 결과를 한 번 알린다. 목록은 화면에 들어올 때 새로 읽으므로 삭제한 상품은 빠져 있다.
 */
export default async function ProductsPage({ searchParams }: PageProps<'/products'>) {
  const params = await searchParams;
  const deleted = parseDeletedResult(params[DELETED_PARAM]);
  // 안내가 있든 없든 같은 모양으로 감싼다 — 조건을 바꿔 안내가 빠져도 목록이 다시 마운트되지 않게
  return (
    <div className="flex flex-col gap-6">
      {deleted && <ProductDeletedNotice result={deleted} />}
      <ProductList query={parseProductQuery(params)} />
    </div>
  );
}
