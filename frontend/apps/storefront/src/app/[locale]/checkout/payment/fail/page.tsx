import type { Metadata } from 'next';
import { PaymentFailed } from '@/components/checkout/PaymentFailed';
import { readFailReturn } from '@/lib/checkout/payment';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.payment.failTitle, robots: { index: false } };
}

/** 결제 실패 복귀(#27) — 토스가 `code` · `message` · `orderId`(pgOrderId) 를 붙인다(`message` 는 읽지 않는다). API 를 부르지 않는다 */
export default async function PaymentFailPage({
  searchParams,
}: PageProps<'/[locale]/checkout/payment/fail'>) {
  const failure = readFailReturn(await searchParams);
  const { dict } = await getI18n();
  return (
    <main className="mx-auto w-full max-w-screen-sm px-4 py-12 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{dict.payment.failTitle}</h1>
      <PaymentFailed failure={failure} />
    </main>
  );
}
