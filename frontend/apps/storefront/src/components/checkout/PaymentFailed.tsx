'use client';

import { useDictionary } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { failText, paymentPageHref, type FailReturn } from '@/lib/checkout/payment';

/**
 * 결제 실패 복귀 화면(#27) — 토스가 `?code&message&orderId(=pgOrderId)` 를 붙여 보낸다(승인 전이라 청구는 없다).
 * 사용자 취소(`PAY_PROCESS_CANCELED`)는 취소 문구, 그 밖은 일반 실패 문구다. 쿼리의 `message` 는 보이지 않는다(지어낸 링크로 임의 문구를 띄울 수 있다).
 * 다시 결제는 복귀 쿼리의 주문(`order`)이 있을 때 결제 화면으로, 없으면 장바구니로 간다.
 */
export function PaymentFailed({ failure }: { failure: FailReturn }) {
  const t = useDictionary().payment;
  const checkoutText = useDictionary().checkout;
  const message = failText(failure, t);
  const link = 'text-sm underline underline-offset-4';

  return (
    <div className="flex flex-col items-center gap-4 py-16 text-center">
      <p role="alert" className="text-sm">
        {message}
      </p>
      {failure.orderId ? (
        <Link href={paymentPageHref(failure.orderId)} className={link}>
          {t.payAgain}
        </Link>
      ) : (
        <Link href="/cart" className={link}>
          {checkoutText.backToCart}
        </Link>
      )}
    </div>
  );
}
