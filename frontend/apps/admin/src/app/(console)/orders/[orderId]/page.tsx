import type { Metadata } from 'next';
import { OrderDetailScreen } from '@/components/order/OrderDetailScreen';

export const metadata: Metadata = { title: '주문 상세' };

/** 어드민 주문 상세 · 상태 변경(#30). 상태 변경 응답으로 화면을 바로 바꿔 그려서 클라이언트에서 읽는다 */
export default async function OrderDetailPage({ params }: PageProps<'/orders/[orderId]'>) {
  const { orderId } = await params;
  return <OrderDetailScreen orderId={orderId} />;
}
