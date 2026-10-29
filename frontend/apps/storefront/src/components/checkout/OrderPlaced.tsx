'use client';

import { useEffect, useRef } from 'react';
import { useDictionary, useLocale } from '@/components/i18n/LocaleProvider';
import { formatDueAt, type OrderCreated } from '@/lib/checkout/order';
import { fill } from '@/lib/i18n/format';
import { QuoteSummary } from './QuoteSummary';

/**
 * 주문 생성 뒤 결제 단계 자리 표시(#26). 결제위젯 연동은 #27(BE #14 뒤)이다 — 지금은 주문 번호 · 결제 기한 · 주문 응답의 금액만 보인다.
 * 금액은 주문 응답(서버가 다시 계산한 값)을 그대로 보인다. 장바구니는 서버가 비우지 않는다(BE #13 C3) — 결제 연동에서 정한다.
 */
export function OrderPlaced({ order }: { order: OrderCreated }) {
  const t = useDictionary().checkout;
  const locale = useLocale();
  const heading = useRef<HTMLHeadingElement>(null);

  // 화면이 통째로 바뀌므로 제목으로 포커스를 옮겨 스크린리더가 결과를 읽게 한다
  useEffect(() => heading.current?.focus(), []);

  return (
    <section aria-labelledby="order-placed" className="flex max-w-xl flex-col gap-8">
      <div className="flex flex-col gap-2">
        <h2 id="order-placed" ref={heading} tabIndex={-1} className="text-xl outline-none">
          {t.orderPlaced}
        </h2>
        <p className="text-sm text-muted-fg">
          {t.orderNumber} <span className="text-fg">{order.orderId}</span>
        </p>
      </div>
      <QuoteSummary quote={order} />
      <div className="flex flex-col gap-2 bg-muted px-5 py-4 text-sm">
        <p>{fill(t.paymentDue, { time: formatDueAt(order.paymentDueAt, locale) })}</p>
        <p className="text-muted-fg">{t.paymentPending}</p>
      </div>
    </section>
  );
}
