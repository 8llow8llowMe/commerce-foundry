'use client';

import { Button, cx } from '@cf/ui';
import { useEffect, useId, useRef, useState } from 'react';
import { CloseIcon } from '@/components/header/HeaderIcons';
import {
  displayPrice,
  resolveSku,
  selectOption,
  type OptionSelection,
} from '@/lib/catalog/option-selection';
import type { DeliveryNote } from '@/lib/catalog/delivery-note';
import { purchaseTarget } from '@/lib/catalog/purchase-target';
import type { ProductDetail } from '@/lib/catalog/types';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import { CartActionNotice } from '@/components/cart/CartActionNotice';
import { useCartAction } from '@/lib/cart/use-cart-action';
import { fill } from '@/lib/i18n/format';
import { CustomOrderFields, type CustomOrderValues } from './CustomOrderFields';
import { OptionGroupsField } from './OptionGroupsField';

/** 클라이언트로 넘기는 상세 데이터. 큰 descriptionHtml 은 서버에서만 쓴다 */
export type PurchaseProduct = Omit<ProductDetail, 'descriptionHtml' | 'images' | 'categoryPath'>;

/** 제한이 없을 때(maxQuantityPerOrder null) 화면에서 고를 수 있는 최대 수량 */
const QUANTITY_CAP = 99;

/**
 * 가격 · 옵션 선택 · 수량 · 맞춤 제작 입력. 옵션 조합으로 SKU 를 찾아 그 SKU 의 서버 판매가 · 상태를 보인다.
 * 판매중 SKU 가 정해지고 맞춤 제작 필수 칸이 채워지면 장바구니에 담는다(#26 — 회원은 서버, 비회원은 로컬).
 * 담은 뒤에는 버튼 아래에 결과와 장바구니 링크를 보인다. 입력값이 상품 정의에 맞는지는 주문 생성이 본다.
 *
 * 모바일에서는 갤러리가 첫 화면을 차지하므로 하단에 가격 · 배송 안내 + 구매 버튼 바를 고정한다(#60).
 * 패널의 담기 버튼이 보이면 바를 숨긴다. 바의 구매하기는 옵션 · 수량 · 담기를 담은 바텀시트(<dialog>)를 연다 —
 * 시트와 패널은 같은 선택 상태를 쓴다. 맞춤 제작 상품은 입력칸이 길어 시트 대신 패널로 스크롤한다.
 */
export function PurchasePanel({
  product,
  delivery = null,
}: {
  product: PurchaseProduct;
  /** 가격 아래 배송 안내. 첫 줄은 하단 구매 바에도 보인다 */
  delivery?: DeliveryNote | null;
}) {
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
  const sheetRadioPrefix = useId();
  const sheetNoticeId = useId();
  const sheetTitleId = useId();
  const panelRef = useRef<HTMLDivElement>(null);
  const ctaRef = useRef<HTMLDivElement>(null);
  const sheetRef = useRef<HTMLDialogElement>(null);
  const [panelInView, setPanelInView] = useState(false);
  const [sheetOpen, setSheetOpen] = useState(false);
  const cart = useCartAction();

  useEffect(() => {
    const cta = ctaRef.current;
    if (!cta) return;
    const observer = new IntersectionObserver(
      ([entry]) => setPanelInView(entry?.isIntersecting ?? false),
      {
        // 패널의 수량 · 담기 버튼 묶음이 하단 바(약 80px) 위로 올라와 보일 때만 바를 숨긴다.
        // 가격 줄을 기준으로 하면 화면 높이에 따라 첫 화면부터 바가 숨어 구매 버튼이 사라진다.
        rootMargin: '0px 0px -80px 0px',
      },
    );
    observer.observe(cta);
    return () => observer.disconnect();
  }, []);

  // 시트가 열린 동안: 뒤 페이지 스크롤을 막고, lg 이상으로 넓어지면(시트가 display:none 이 된다) 닫는다 —
  // 보이지 않는 modal 이 남으면 페이지 전체가 inert 가 된다
  useEffect(() => {
    if (!sheetOpen) return;
    const root = document.documentElement;
    const previousOverflow = root.style.overflow;
    root.style.overflow = 'hidden';
    const wide = window.matchMedia('(min-width: 64rem)');
    const closeOnWide = () => {
      if (wide.matches) sheetRef.current?.close();
    };
    wide.addEventListener('change', closeOnWide);
    return () => {
      root.style.overflow = previousOverflow;
      wide.removeEventListener('change', closeOnWide);
    };
  }, [sheetOpen]);

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
  const openPurchase = () => {
    if (purchaseTarget(product) === 'panel') return goToPanel();
    sheetRef.current?.showModal();
    setSheetOpen(true);
  };

  const select = (groupId: string, valueId: string) => {
    cart.clear();
    setSelection((current) => selectOption(product.skus, current, groupId, valueId));
  };
  const changeQuantity = (next: number) => {
    cart.clear();
    setQuantity(Math.min(maxQuantity, Math.max(1, next)));
  };

  const missingRequired =
    product.customOrder?.fields.some(
      (field) => field.required && !(customValues[field.fieldKey] ?? '').trim(),
    ) ?? false;
  const canAdd = productOnSale && sku?.saleStatus.code === 'ON_SALE' && !missingRequired;

  const notice = !productOnSale
    ? product.saleStatus.name
    : groups.length > 0 && !sku
      ? t.selectOptions
      : sku && sku.saleStatus.code !== 'ON_SALE'
        ? fill(t.optionStatus, { status: sku.saleStatus.name })
        : missingRequired
          ? t.fillRequired
          : t.ready;

  const addToCart = () => {
    if (!canAdd || !sku) return;
    void cart.add([
      {
        productId: product.productId,
        skuId: sku.skuId,
        quantity,
        customOptions: Object.entries(customValues)
          .filter(([, value]) => value.trim() !== '')
          .map(([fieldKey, value]) => ({ fieldKey, value })),
        maxQuantity: product.maxQuantityPerOrder,
      },
    ]);
  };

  const priceText = (
    <>
      {formatMoney(price.salePrice)}
      {price.from && (
        <>
          <span aria-hidden>~</span>
          <span className="sr-only"> {dict.price.from}</span>
        </>
      )}
    </>
  );
  const quantityField = (labelId: string) => (
    <>
      <div className="flex items-center justify-between">
        <span className="text-xs tracking-[0.15em] uppercase" id={labelId}>
          {t.quantity}
        </span>
        <div
          className="flex items-center border border-border"
          role="group"
          aria-labelledby={labelId}
        >
          <button
            type="button"
            aria-label={t.decrease}
            disabled={quantity <= 1}
            onClick={() => changeQuantity(quantity - 1)}
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
            onClick={() => changeQuantity(quantity + 1)}
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
    </>
  );
  const addButton = (describedBy: string) => (
    <Button
      disabled={!canAdd || cart.pending || cart.coolingDown}
      onClick={addToCart}
      className="h-12 w-full"
      aria-describedby={describedBy}
    >
      {cart.pending ? t.adding : t.addToCart}
    </Button>
  );

  return (
    <div ref={panelRef} className="flex scroll-mt-32 flex-col gap-6">
      <div className="flex flex-col gap-2">
        <p className="flex flex-wrap items-baseline gap-x-3 text-lg">
          {price.discountRate !== null && (
            <span className="font-medium">
              <span className="sr-only">{dict.price.discountRate} </span>
              {price.discountRate}%
            </span>
          )}
          <span>{priceText}</span>
          {price.listPrice && (
            <del className="text-sm text-muted-fg">
              <span className="sr-only">{dict.price.listPrice} </span>
              {formatMoney(price.listPrice)}
            </del>
          )}
        </p>
        {delivery && (
          <ul className="flex flex-col gap-1 text-sm text-muted-fg">
            {delivery.lines.map((line) => (
              <li key={line}>{line}</li>
            ))}
          </ul>
        )}
      </div>

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
          onChange={(key, value) => {
            cart.clear();
            setCustomValues((current) => ({ ...current, [key]: value }));
          }}
        />
      )}

      <div ref={ctaRef} className="flex flex-col gap-3 pt-4">
        {quantityField(quantityLabelId)}
        {addButton(noticeId)}
        <CartActionNotice id={noticeId} result={cart.result} notice={notice} />
      </div>

      {/* 모바일 하단 고정 구매 바 — 패널의 담기 버튼이 보이면 숨긴다 */}
      <div
        aria-hidden={panelInView || undefined}
        className={cx(
          'fixed inset-x-0 bottom-0 z-30 flex items-center gap-4 border-t border-border bg-bg px-4 py-3 transition lg:hidden',
          panelInView && 'pointer-events-none translate-y-full opacity-0',
        )}
      >
        <p className="min-w-0 flex-1 text-sm">
          <span className="block">{priceText}</span>
          {delivery && (
            <span className="block truncate text-xs text-muted-fg">{delivery.lines[0]}</span>
          )}
        </p>
        <Button
          className="h-11 shrink-0 px-6"
          tabIndex={panelInView ? -1 : undefined}
          onClick={openPurchase}
        >
          {productOnSale ? t.buy : product.saleStatus.name}
        </Button>
      </div>

      {/* 모바일 구매 바텀시트 — 바깥(배경)을 누르거나 ESC 로 닫는다. 담은 결과도 시트 안에 보인다.
          dialog 자신은 여백 없이 두고 안쪽 wrapper 가 여백을 갖는다 — dialog 를 직접 누른 것만 배경 클릭이다 */}
      <dialog
        ref={sheetRef}
        aria-labelledby={sheetTitleId}
        onClick={(event) => {
          if (event.target === event.currentTarget) event.currentTarget.close();
        }}
        onClose={() => setSheetOpen(false)}
        className="inset-x-0 top-auto bottom-0 m-0 max-h-[85svh] w-full max-w-none overflow-y-auto overscroll-contain bg-bg p-0 text-fg backdrop:bg-fg/40 lg:hidden"
      >
        <div className="px-4 pt-4 pb-[calc(1.5rem+env(safe-area-inset-bottom))]">
          <div className="flex items-start justify-between gap-4">
            <p id={sheetTitleId} className="min-w-0 text-sm">
              <span className="block truncate">{product.name}</span>
              <span className="block">{priceText}</span>
            </p>
            <button
              type="button"
              aria-label={t.closeSheet}
              onClick={() => sheetRef.current?.close()}
              className="-mt-2 -mr-2 inline-flex size-10 shrink-0 items-center justify-center"
            >
              <CloseIcon />
            </button>
          </div>
          <div className="mt-6 flex flex-col gap-6">
            <OptionGroupsField
              groups={groups}
              skus={product.skus}
              selection={selection}
              namePrefix={sheetRadioPrefix}
              onSelect={select}
            />
            <div className="flex flex-col gap-3">
              {quantityField(`${sheetTitleId}-quantity`)}
              {addButton(sheetNoticeId)}
              <CartActionNotice id={sheetNoticeId} result={cart.result} notice={notice} />
            </div>
          </div>
        </div>
      </dialog>
    </div>
  );
}
