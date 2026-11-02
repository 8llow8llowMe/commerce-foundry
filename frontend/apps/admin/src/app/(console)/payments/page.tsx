import type { Metadata } from 'next';
import { PaymentList } from '@/components/payment/PaymentList';
import { parsePaymentQuery } from '@/lib/payment/query';

export const metadata: Metadata = { title: '결제 확인' };

/** 어드민 결제 확인 목록(#30). 조건은 주소가 정본이다 — 허용 값만 읽고 나머지는 "전체" 다 */
export default async function PaymentsPage({ searchParams }: PageProps<'/payments'>) {
  return <PaymentList query={parsePaymentQuery(await searchParams)} />;
}
