'use client';

import { Button, cx } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import {
  displayPrice,
  resolveSku,
  selectOption,
  type OptionSelection,
} from '@/lib/catalog/option-selection';
import type { ProductDetail } from '@/lib/catalog/types';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import { fill } from '@/lib/i18n/format';
import { CustomOrderFields, type CustomOrderValues } from './CustomOrderFields';
import { OptionGroupsField } from './OptionGroupsField';

/** 클라이언트로 넘기는 상세 데이터. 큰 descriptionHtml 은 서버에서만 쓴다 */
export type PurchaseProduct = Omit<ProductDetail, 'descriptionHtml' | 'images' | 'categoryPath'>;

/** 제한이 없을 때(maxQuantityPerOrder null) 화면에서 고를 수 있는 최대 수량 */
const QUANTITY_CAP = 99;

/**
 * 가격 · 옵션 선택 · 수량 · 맞춤 제작 입력. 옵션 조합으로 SKU 를 찾아 그 SKU 의 서버 판매가 · 상태를 보인다.
 * 장바구니 담기 연결은 #26 이다 — 지금은 선택이 끝나면 버튼 자리만 활성 조건을 보여 준다.
 *
 * 모바일에서는 갤러리가 첫 화면을 차지하므로 하단에 가격 + 구매 버튼 바를 고정한다(#60).
 * 패널이 화면에 들어오면 바를 숨기고, 버튼은 패널로 스크롤해 첫 옵션에 포커스를 준다.
 */
export function PurchasePanel({ product }: { product: PurchaseProduct }) {
  const groups = product.optionGroups;
  const dict = useDictionary();
  const t = dict.product.purchase;
  const formatMoney = useFormatMoney();
  const [selection, setSelection] = useState<OptionSelection>({});
  const [quantity, setQuantity] = useState(1);
  const [customValues, setCustomValues] = useState<CustomOrderValues>({});
  const quantityLabelId = useId();
  const noticeId = useId();
  const radioPrefix = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const [panelInView, setPanelInView] = useState(false);

  useEffect(() => {
    const panel = panelRef.current;
    if (!panel) return;
    const observer = new IntersectionObserver(
      ([entry]) => setPanelInView(entry?.isIntersecting ?? false),
      {
        // 패널 윗부분(가격 · 옵션)이 화면 아래쪽 1/4 안으로 들어오면 보이는 것으로 본다
        rootMargin: '0px 0px -25% 0px',
      },
    );
    observer.observe(panel);
    return () => observer.disconnect();
  }, []);

  const goToPanel = () => {
    const panel = panelRef.current;
    if (!panel) return;
    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
    // 아직 고르지 않은 첫 그룹의 첫 선택 가능 값, 없으면 수량으로
    const target =
      panel.querySelector<HTMLInputElement>('input[type="radio"]:not(:disabled):not(:checked)') ??
      panel.querySelector<HTMLButtonElement>('button:not(:disabled)');
    target?.focus({ preventScroll: true });
  };

  const sku = resolveSku(groups, product.skus, selection);
  const price = displayPrice(product, sku);
  const maxQuantity = product.maxQuantityPerOrder ?? QUANTITY_CAP;
  const productOnSale = product.saleStatus.code === 'ON_SALE';

  const select = (groupId: string, valueId: string) =>
    setSelection((current) => selectOption(product.skus, current, groupId, valueId));

  const notice = !productOnSale
    ? product.saleStatus.name
    : groups.length > 0 && !sku
      ? t.selectOptions
      : sku && sku.saleStatus.code !== 'ON_SALE'
        ? fill(t.optionStatus, { status: sku.saleStatus.name })
        : t.cartPending;

  return (
    <div ref={panelRef} className="flex scroll-mt-32 flex-col gap-6">
      <p className="flex flex-wrap items-baseline gap-x-3 text-lg">
        {price.discountRate !== null && (
          <span className="font-medium">
            <span className="sr-only">{dict.price.discountRate} </span>
            {price.discountRate}%
          </span>
        )}
        <span>
          {formatMoney(price.salePrice)}
          {price.from && (
            <>
              <span aria-hidden>~</span>
              <span className="sr-only"> {dict.price.from}</span>
            </>
          )}
        </span>
        {price.listPrice && (
          <del className="text-sm text-muted-fg">
            <span className="sr-only">{dict.price.listPrice} </span>
            {formatMoney(price.listPrice)}
          </del>
        )}
      </p>

      <OptionGroupsField
        groups={groups}
        skus={product.skus}
        selection={selection}
        namePrefix={radioPrefix}
        onSelect={select}
      />

      {sku && sku.additionalPrice.amount > 0 && (
        <p className="text-sm text-muted-fg">
          {fill(t.surcharge, { amount: formatMoney(sku.additionalPrice) })}
        </p>
      )}

      {product.customOrder && (
        <CustomOrderFields
          customOrder={product.customOrder}
          values={customValues}
          onChange={(key, value) => setCustomValues((current) => ({ ...current, [key]: value }))}
        />
      )}

      <div className="flex flex-col gap-3 pt-4">
        <div className="flex items-center justify-between">
          <span className="text-xs tracking-[0.15em] uppercase" id={quantityLabelId}>
            {t.quantity}
          </span>
          <div
            className="flex items-center border border-border"
            role="group"
            aria-labelledby={quantityLabelId}
          >
            <button
              type="button"
              aria-label={t.decrease}
              disabled={quantity <= 1}
              onClick={() => setQuantity((q) => Math.max(1, q - 1))}
              className="size-10 disabled:text-muted-fg"
            >
              −
            </button>
            <output aria-live="polite" className="w-10 text-center text-sm">
              {quantity}
            </output>
            <button
              type="button"
              aria-label={t.increase}
              disabled={quantity >= maxQuantity}
              onClick={() => setQuantity((q) => Math.min(maxQuantity, q + 1))}
              className="size-10 disabled:text-muted-fg"
            >
              +
            </button>
          </div>
        </div>
        {product.maxQuantityPerOrder !== null && (
          <p className="text-xs text-muted-fg">
            {fill(t.maxPerOrder, { count: product.maxQuantityPerOrder })}
          </p>
        )}

        <Button disabled className="h-12 w-full" aria-describedby={noticeId}>
          {t.addToCart}
        </Button>
        <p id={noticeId} role="status" className="text-center text-xs text-muted-fg">
          {notice}
        </p>
      </div>

      {/* 모바일 하단 고정 구매 바 — 패널이 보이면 숨긴다 */}
      <div
        aria-hidden={panelInView || undefined}
        className={cx(
          'fixed inset-x-0 bottom-0 z-30 flex items-center gap-4 border-t border-border bg-bg px-4 py-3 transition lg:hidden',
          panelInView && 'pointer-events-none translate-y-full opacity-0',
        )}
      >
        <p className="min-w-0 flex-1 text-sm">
          <span className="block truncate text-xs text-muted-fg">{product.name}</span>
          <span>
            {formatMoney(price.salePrice)}
            {price.from && <span aria-hidden>~</span>}
          </span>
        </p>
        <Button
          className="h-11 shrink-0 px-6"
          tabIndex={panelInView ? -1 : undefined}
          onClick={goToPanel}
        >
          {productOnSale ? t.buy : product.saleStatus.name}
        </Button>
      </div>
    </div>
  );
}
