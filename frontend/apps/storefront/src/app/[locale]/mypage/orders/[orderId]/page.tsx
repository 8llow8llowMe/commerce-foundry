import type { Metadata } from 'next';
import { notFound } from 'next/navigation';
import { AccountShell } from '@/components/account/AccountShell';
import { OrderDetail } from '@/components/orders/OrderDetail';
import { requireSession } from '@/lib/auth/require-session';
import { getI18n } from '@/lib/i18n/server';
import { orderPageHref, readOrderPathId } from '@/lib/orders/history';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.orders.detailTitle, robots: { index: false } };
}

/**
 * 주문 상세 · 결제 전 취소 · 구매 확정(#28 · BE #16). 주문 아이디 형식이 어긋나면 API 를 부르지 않고 not-found 다.
 * 상세 · 취소 · 구매 확정은 브라우저에서 BFF 로 한다(`OrderDetail`).
 */
export default async function OrderDetailPage({
  params,
}: PageProps<'/[locale]/mypage/orders/[orderId]'>) {
  const orderId = readOrderPathId((await params).orderId);
  if (!orderId) notFound();
  await requireSession(orderPageHref(orderId));
  const { dict } = await getI18n();
  return (
    <AccountShell title={dict.orders.detailTitle} back={dict.orders.back} backHref="/mypage/orders">
      <OrderDetail orderId={orderId} />
    </AccountShell>
  );
}
