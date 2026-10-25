'use client';

import { useId } from 'react';
import { useDictionary, useFormatMoney, useLocale } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import type { CartLineView } from '@/lib/cart/line-view';
import { preorderNote } from '@/lib/catalog/preorder';
import { fill } from '@/lib/i18n/format';

/**
 * 장바구니 한 줄 — 상품명 · 옵션 · 맞춤 제작 입력 · 수량 · 금액 · 삭제.
 * 금액은 서버 값만 보인다(회원 줄 금액). 비회원 줄은 단가만 보인다.
 * 판매중이 아니거나 상한을 넘은 줄은 이유를 적고 합계에서 빠진다고 알린다(합계는 서버가 뺀다).
 * 프리오더 상품으로 알려진 줄은 옵션 아래에 발송 예정을 보인다(#60) — 지금은 비회원 줄만 안다.
 */
export function CartLineItem({
  line,
  busy,
  onQuantity,
  onRemove,
}: {
  line: CartLineView;
  busy: boolean;
  onQuantity: (quantity: number) => void;
  onRemove: () => void;
}) {
  const dict = useDictionary();
  const t = dict.cart;
  const formatMoney = useFormatMoney();
  const quantityLabelId = useId();
  const name = line.name ?? t.productUnavailable;
  const onSale = line.status?.code === 'ON_SALE';
  const locale = useLocale();
  // 장바구니 줄은 브라우저에서 읽은 뒤에만 그려져(비회원) 서버 렌더와 날짜가 어긋나지 않는다
  const preorder = preorderNote(line.preorder, locale, dict.product.preorder, new Date());

  const note = !line.status
    ? t.excluded
    : !onSale
      ? `${line.status.name} · ${t.excluded}`
      : line.quantityExceeded
        ? fill(t.exceeded, { count: line.maxQuantity })
        : null;

  return (
    <li className="flex flex-col gap-3 bg-muted px-5 py-6 sm:flex-row sm:items-start sm:justify-between">
      <div className="flex min-w-0 flex-col gap-1">
        {line.name ? (
          <Link
            href={`/products/${line.productId}`}
            className="font-medium underline-offset-4 hover:underline"
          >
            {name}
          </Link>
        ) : (
          <span className="font-medium text-muted-fg">{name}</span>
        )}
        {line.optionName && <span className="text-sm text-muted-fg">{line.optionName}</span>}
        {line.customOptions.length > 0 && (
          <dl className="text-xs text-muted-fg">
            {line.customOptions.map((option) => (
              <div key={option.label} className="flex gap-1">
                <dt>{option.label}</dt>
                <dd>{option.value}</dd>
              </div>
            ))}
          </dl>
        )}
        {preorder && <p className="text-xs">{preorder}</p>}
        {note && <p className="text-xs text-danger">{note}</p>}
      </div>

      <div className="flex items-center justify-between gap-6 sm:justify-end">
        <div
          className="flex items-center border border-border"
          role="group"
          aria-labelledby={quantityLabelId}
        >
          <span id={quantityLabelId} className="sr-only">
            {t.quantity}
          </span>
          <button
            type="button"
            aria-label={t.decrease}
            disabled={busy || line.quantity <= 1}
            onClick={() => onQuantity(line.quantity - 1)}
            className="size-9 disabled:text-muted-fg"
          >
            −
          </button>
          <output aria-live="polite" className="w-9 text-center text-sm">
            {line.quantity}
          </output>
          <button
            type="button"
            aria-label={t.increase}
            disabled={busy || line.quantity >= line.maxQuantity}
            onClick={() => onQuantity(line.quantity + 1)}
            className="size-9 disabled:text-muted-fg"
          >
            +
          </button>
        </div>

        <p className="w-28 text-right text-sm">
          {line.lineAmount
            ? formatMoney(line.lineAmount)
            : line.unitPrice && fill(t.each, { amount: formatMoney(line.unitPrice) })}
        </p>

        <button
          type="button"
          disabled={busy}
          onClick={onRemove}
          aria-label={fill(t.removeItem, { name })}
          className="text-xs text-muted-fg underline-offset-4 hover:text-fg hover:underline disabled:opacity-50"
        >
          {t.remove}
        </button>
      </div>
    </li>
  );
}
