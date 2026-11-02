import type { Metadata } from 'next';
import { AccountShell } from '@/components/account/AccountShell';
import { OrderList } from '@/components/orders/OrderList';
import { requireSession } from '@/lib/auth/require-session';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.orders.title, robots: { index: false } };
}

/** 주문 내역(#28 · BE #16). 목록은 브라우저에서 BFF 로 읽는다 — 만료된 access 를 BFF 가 재발급할 수 있게 */
export default async function OrdersPage() {
  await requireSession('/mypage/orders');
  const { dict } = await getI18n();
  return (
    <AccountShell title={dict.orders.title} back={dict.account.back}>
      <OrderList />
    </AccountShell>
  );
}
