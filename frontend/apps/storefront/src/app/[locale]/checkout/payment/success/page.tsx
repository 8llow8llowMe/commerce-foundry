import type { Metadata } from 'next';
import { PaymentResult } from '@/components/checkout/PaymentResult';
import { requireSession } from '@/lib/auth/require-session';
import { PAYMENT_SUCCESS_PATH, readSuccessReturn } from '@/lib/checkout/payment';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.payment.successTitle, robots: { index: false } };
}

/**
 * 결제 성공 복귀(#27) — 토스가 `paymentKey` · `orderId`(pgOrderId) · `amount` 를 붙인다. 승인은 브라우저가 BFF 로 보낸다(`PaymentResult`).
 * 쿼리는 여기서 거른다 — 어긋나면 승인을 보내지 않는다. 로그인 뒤에는 같은 주소(같은 쿼리)로 돌아와 같은 멱등 키로 다시 확인한다.
 */
export default async function PaymentSuccessPage({
  searchParams,
}: PageProps<'/[locale]/checkout/payment/success'>) {
  const query = await searchParams;
  const result = readSuccessReturn(query);
  const returnPath = result
    ? `${PAYMENT_SUCCESS_PATH}?${new URLSearchParams({
        paymentKey: result.confirm.paymentKey,
        orderId: result.confirm.pgOrderId,
        amount: String(result.confirm.amount.amount),
        ...(result.orderId ? { order: result.orderId } : {}),
      })}`
    : '/cart';
  await requireSession(returnPath);
  const { dict } = await getI18n();
  return (
    <main className="mx-auto w-full max-w-screen-sm px-4 py-12 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{dict.payment.successTitle}</h1>
      <PaymentResult result={result} returnPath={returnPath} />
    </main>
  );
}
