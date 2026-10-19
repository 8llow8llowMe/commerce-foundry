'use client';

import { formatMoney } from '@cf/api-client';
import { Button, cx } from '@cf/ui';
import { useId, useState } from 'react';
import {
  displayPrice,
  optionValueState,
  resolveSku,
  type OptionSelection,
} from '@/lib/catalog/option-selection';
import type { ProductDetail } from '@/lib/catalog/types';
import { CustomOrderFields, type CustomOrderValues } from './CustomOrderFields';

/** 클라이언트로 넘기는 상세 데이터. 큰 descriptionHtml 은 서버에서만 쓴다 */
export type PurchaseProduct = Omit<ProductDetail, 'descriptionHtml' | 'images' | 'categoryPath'>;

/** 제한이 없을 때(maxQuantityPerOrder null) 화면에서 고를 수 있는 최대 수량 */
const QUANTITY_CAP = 99;

/**
 * 가격 · 옵션 선택 · 수량 · 맞춤 제작 입력. 옵션 조합으로 SKU 를 찾아 그 SKU 의 서버 판매가 · 상태를 보인다.
 * 장바구니 담기 연결은 #26 이다 — 지금은 선택이 끝나면 버튼 자리만 활성 조건을 보여 준다.
 */
export function PurchasePanel({ product }: { product: PurchaseProduct }) {
  const groups = product.optionGroups;
  const [selection, setSelection] = useState<OptionSelection>({});
  const [quantity, setQuantity] = useState(1);
  const [customValues, setCustomValues] = useState<CustomOrderValues>({});
  const quantityLabelId = useId();
  const noticeId = useId();

  const sku = resolveSku(groups, product.skus, selection);
  const price = displayPrice(product, sku);
  const maxQuantity = product.maxQuantityPerOrder ?? QUANTITY_CAP;
  const productOnSale = product.saleStatus.code === 'ON_SALE';

  const select = (groupId: string, valueId: string) => {
    setSelection((current) => {
      const next = { ...current, [groupId]: valueId };
      // 새 값과 함께 살 수 없게 된 다른 그룹의 선택은 푼다
      for (const [otherGroup, otherValue] of Object.entries(next)) {
        if (otherGroup === groupId) continue;
        if (optionValueState(product.skus, next, otherGroup, otherValue) !== 'available') {
          delete next[otherGroup];
        }
      }
      return next;
    });
  };

  const notice = !productOnSale
    ? product.saleStatus.name
    : groups.length > 0 && !sku
      ? '옵션을 선택해 주세요.'
      : sku && sku.saleStatus.code !== 'ON_SALE'
        ? `선택한 옵션은 ${sku.saleStatus.name} 입니다.`
        : '장바구니 담기는 준비 중입니다.';

  return (
    <div className="flex flex-col gap-6">
      <p className="flex flex-wrap items-baseline gap-x-3 text-lg">
        {price.discountRate !== null && (
          <span className="font-medium">
            <span className="sr-only">할인율 </span>
            {price.discountRate}%
          </span>
        )}
        <span>
          {formatMoney(price.salePrice)}
          {price.from && (
            <>
              <span aria-hidden>~</span>
              <span className="sr-only"> 부터</span>
            </>
          )}
        </span>
        {price.listPrice && (
          <del className="text-sm text-muted-fg">
            <span className="sr-only">정가 </span>
            {formatMoney(price.listPrice)}
          </del>
        )}
      </p>

      {groups.map((group) => (
        <fieldset key={group.optionGroupId} className="flex flex-col gap-3">
          <legend className="mb-3 text-xs tracking-[0.15em] uppercase">{group.name}</legend>
          <div className="flex flex-wrap gap-2">
            {group.values.map((value) => {
              // 어떤 조합으로도 살 수 없는 값만 막는다. 지금 고른 다른 옵션과 안 맞는 값은 취소선으로 보이되
              // 누를 수 있다 — 누르면 select() 가 충돌하는 다른 선택을 푼다.
              const disabled =
                optionValueState(product.skus, {}, group.optionGroupId, value.optionValueId) !==
                'available';
              const state = optionValueState(
                product.skus,
                selection,
                group.optionGroupId,
                value.optionValueId,
              );
              const checked = selection[group.optionGroupId] === value.optionValueId;
              return (
                <label
                  key={value.optionValueId}
                  className={cx(
                    'relative inline-flex min-h-10 min-w-12 cursor-pointer items-center justify-center border px-4 text-sm transition',
                    'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary',
                    checked ? 'border-fg bg-fg text-bg' : 'border-border hover:border-fg',
                    !checked && state !== 'available' && 'text-muted-fg line-through',
                    disabled && 'cursor-not-allowed hover:border-border',
                  )}
                >
                  <input
                    type="radio"
                    name={group.optionGroupId}
                    value={value.optionValueId}
                    checked={checked}
                    disabled={disabled}
                    onChange={() => select(group.optionGroupId, value.optionValueId)}
                    className="sr-only"
                  />
                  {value.name}
                  {state === 'soldOut' && <span className="sr-only"> (품절)</span>}
                  {state === 'unavailable' && <span className="sr-only"> (선택 불가)</span>}
                </label>
              );
            })}
          </div>
        </fieldset>
      ))}

      {sku && sku.additionalPrice.amount > 0 && (
        <p className="text-sm text-muted-fg">옵션 추가금 +{formatMoney(sku.additionalPrice)}</p>
      )}

      {product.customOrder && (
        <CustomOrderFields
          customOrder={product.customOrder}
          values={customValues}
          onChange={(key, value) => setCustomValues((current) => ({ ...current, [key]: value }))}
        />
      )}

      <div className="flex flex-col gap-3 border-t border-border pt-6">
        <div className="flex items-center justify-between">
          <span className="text-xs tracking-[0.15em] uppercase" id={quantityLabelId}>
            수량
          </span>
          <div
            className="flex items-center border border-border"
            role="group"
            aria-labelledby={quantityLabelId}
          >
            <button
              type="button"
              aria-label="수량 줄이기"
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
              aria-label="수량 늘리기"
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
            한 번에 최대 {product.maxQuantityPerOrder}개까지 주문할 수 있습니다.
          </p>
        )}

        <Button disabled className="h-12 w-full" aria-describedby={noticeId}>
          장바구니 담기
        </Button>
        <p id={noticeId} role="status" className="text-center text-xs text-muted-fg">
          {notice}
        </p>
      </div>
    </div>
  );
}
