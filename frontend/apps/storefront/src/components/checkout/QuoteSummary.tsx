'use client';

import type { Money } from '@cf/api-client';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import type { OrderQuote, ShippingQuote } from '@/lib/checkout/quote';
import { fill } from '@/lib/i18n/format';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 주문서 결제 금액(#26) — 상품 합계 · 배송비 · 총액은 견적 응답 값을 그대로 보인다(더하거나 빼지 않는다).
 * 배송비 아래에는 서버가 준 근거(무료배송 적용 · 기본 배송비 · 도서산간 · 해외 무게 요금)를 덧붙인다.
 */
export function QuoteSummary({ quote }: { quote: OrderQuote }) {
  const t = useDictionary().checkout;
  const formatMoney = useFormatMoney();
  const { amounts } = quote;

  return (
    <dl className="flex flex-col gap-3 text-sm">
      <div className="flex items-baseline justify-between gap-4">
        <dt>{t.itemsAmount}</dt>
        <dd>{formatMoney(amounts.itemsAmount)}</dd>
      </div>
      <div className="flex flex-col gap-1">
        <div className="flex items-baseline justify-between gap-4">
          <dt>{t.shippingFee}</dt>
          <dd>{formatMoney(amounts.shippingFee)}</dd>
        </div>
        <dd>
          <ul className="flex flex-col gap-0.5 text-xs text-muted-fg">
            {shippingNotes(quote.shipping, t, formatMoney).map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </dd>
      </div>
      <div className="flex items-baseline justify-between gap-4 border-t border-border pt-3">
        <dt className="font-medium">{t.totalAmount}</dt>
        <dd className="text-lg font-medium">{formatMoney(amounts.totalAmount)}</dd>
      </div>
    </dl>
  );
}

/**
 * 배송비 근거 문구. 서버 값을 옮겨 적을 뿐 계산하지 않는다 — 무료배송이면 기본 배송비 대신 "무료배송",
 * 도서산간 추가금은 0 이 아닐 때, 해외 무게 요금은 있을 때만. 무료배송 기준은 아직 못 넘었을 때만 안내한다.
 */
export function shippingNotes(
  shipping: ShippingQuote,
  t: Dictionary['checkout'],
  formatMoney: (money: Money) => string,
): string[] {
  const notes: string[] = [];
  notes.push(
    shipping.freeShippingApplied
      ? t.freeShipping
      : fill(t.baseFee, { amount: formatMoney(shipping.baseFee) }),
  );
  if (shipping.weightFee) {
    const amount = formatMoney(shipping.weightFee);
    notes.push(
      shipping.shippingZoneName
        ? fill(t.weightFee, { amount, zone: shipping.shippingZoneName })
        : fill(t.weightFeeUnnamed, { amount }),
    );
  }
  if (shipping.remoteAreaSurcharge.amount > 0) {
    const amount = formatMoney(shipping.remoteAreaSurcharge);
    notes.push(
      shipping.remoteAreaName
        ? fill(t.remoteArea, { amount, name: shipping.remoteAreaName })
        : fill(t.remoteAreaUnnamed, { amount }),
    );
  }
  if (!shipping.freeShippingApplied && shipping.freeShippingThreshold) {
    notes.push(
      fill(t.freeShippingThreshold, { amount: formatMoney(shipping.freeShippingThreshold) }),
    );
  }
  return notes;
}
