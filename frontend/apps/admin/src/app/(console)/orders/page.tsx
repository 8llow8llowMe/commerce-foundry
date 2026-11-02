import type { Metadata } from 'next';
import { OrderList } from '@/components/order/OrderList';
import { parseOrderQuery } from '@/lib/order/query';

export const metadata: Metadata = { title: '주문' };

/** 어드민 주문 목록(#30). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function OrdersPage({ searchParams }: PageProps<'/orders'>) {
  return <OrderList query={parseOrderQuery(await searchParams)} />;
}
