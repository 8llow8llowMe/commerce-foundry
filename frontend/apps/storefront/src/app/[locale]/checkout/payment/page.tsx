import type { Metadata } from 'next';
import { PaymentView } from '@/components/checkout/PaymentView';
import { Link } from '@/components/i18n/Link';
import { requireSession } from '@/lib/auth/require-session';
import { paymentPageHref, readOrderId, widgetClientKey } from '@/lib/checkout/payment';
import { tossClientKey } from '@/lib/env';
import { getI18n } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return { title: dict.payment.title, robots: { index: false } };
}

/**
 * 결제(#27) — 주문 생성 뒤 `?orderId=` 로 온다. 결제 준비 · 결제위젯은 브라우저에서 한다(`PaymentView`).
 * 결제 API 가 로그인 회원만 받으므로 진입부터 로그인을 요구한다. 주문 아이디 형식이 어긋나면 API 를 부르지 않는다.
 */
export default async function PaymentPage({
  searchParams,
}: PageProps<'/[locale]/checkout/payment'>) {
  const orderId = readOrderId((await searchParams).orderId);
  await requireSession(orderId ? paymentPageHref(orderId) : '/cart');
  const { dict } = await getI18n();
  return (
    <main className="mx-auto w-full max-w-screen-sm px-4 py-12 lg:py-16">
      <h1 className="mb-8 font-serif text-[1.75rem] leading-tight">{dict.payment.title}</h1>
      {orderId ? (
        <PaymentView orderId={orderId} clientKey={widgetClientKey(tossClientKey)} />
      ) : (
        <div className="flex flex-col items-center gap-4 py-16 text-center">
          <p role="alert" className="text-sm text-danger">
            {dict.payment.invalidOrder}
          </p>
          <Link href="/cart" className="text-sm underline underline-offset-4">
            {dict.checkout.backToCart}
          </Link>
        </div>
      )}
    </main>
  );
}
