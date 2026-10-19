'use client';

import { Button, cx } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { useEffect, useId, useRef, useState } from 'react';
import { useDictionary, useFormatMoney } from '@/components/i18n/LocaleProvider';
import { OptionGroupsField } from '@/components/product/detail/OptionGroupsField';
import {
  lookCartLines,
  lookPickable,
  type LookPick,
  type LookProduct,
} from '@/lib/catalog/look-cart';
import { selectOption } from '@/lib/catalog/option-selection';
import { fill } from '@/lib/i18n/format';

/**
 * Shop the Look 구성품 목록 — 메인 04 영역과 상품 상세가 함께 쓴다.
 * 상품마다 함께 담기 여부와 옵션을 고르고, lookCartLines 로 담을 SKU 를 정한다.
 * 장바구니 연결은 #26 이다 — 지금은 버튼을 막고 무엇이 남았는지만 알려 준다.
 * 합계는 보이지 않는다(금액은 서버 값만 — 장바구니가 계산한다).
 *
 * floatingAction 이면 모바일에서 목록이 화면에 있는 동안 담기 버튼을 하단에 고정한다(메인 04, #59).
 * 상품 상세는 구매 패널이 이미 하단 바를 쓰므로 끈다.
 */
export function LookPicker({
  products,
  currentProductId,
  floatingAction = false,
}: {
  products: LookProduct[];
  /** 상품 상세에서 지금 보는 상품 */
  currentProductId?: string;
  floatingAction?: boolean;
}) {
  const rootRef = useRef<HTMLDivElement>(null);
  const [inView, setInView] = useState(false);
  useEffect(() => {
    const root = rootRef.current;
    if (!floatingAction || !root) return;
    const observer = new IntersectionObserver(([entry]) =>
      setInView(entry?.isIntersecting ?? false),
    );
    observer.observe(root);
    return () => observer.disconnect();
  }, [floatingAction]);

  const baseId = useId();
  const noticeId = useId();
  const t = useDictionary().look;
  const formatMoney = useFormatMoney();
  const [picks, setPicks] = useState<LookPick[]>(() =>
    products.map((product) => ({ product, included: lookPickable(product), selection: {} })),
  );

  const update = (productId: string, change: (pick: LookPick) => LookPick) =>
    setPicks((current) =>
      current.map((pick) => (pick.product.productId === productId ? change(pick) : pick)),
    );

  const { lines, incomplete } = lookCartLines(picks);
  const notice =
    incomplete.length > 0
      ? fill(t.selectOptions, { names: incomplete.join(', ') })
      : lines.length === 0
        ? t.pickItems
        : t.cartPending;

  return (
    <div ref={rootRef} className="flex flex-col gap-6">
      <ul className="flex flex-col gap-6">
        {picks.map(({ product, included, selection }, index) => {
          const pickable = lookPickable(product);
          const checkboxId = `${baseId}-${index}`;
          const current = product.productId === currentProductId;
          return (
            <li key={product.productId} className="flex gap-4">
              <input
                id={checkboxId}
                type="checkbox"
                checked={included}
                disabled={!pickable}
                onChange={(event) =>
                  update(product.productId, (pick) => ({
                    ...pick,
                    included: event.target.checked,
                  }))
                }
                className="mt-1 size-4 shrink-0 accent-fg"
              />
              <div className="aspect-[3/4] w-13 shrink-0 self-start bg-muted lg:w-16">
                {product.thumbnail && (
                  // eslint-disable-next-line @next/next/no-img-element
                  <img
                    src={product.thumbnail.url}
                    alt=""
                    loading="lazy"
                    decoding="async"
                    className="size-full object-cover"
                  />
                )}
              </div>
              <div className="flex min-w-0 flex-1 flex-col gap-3 text-sm">
                {/* 이름(굵게)과 가격을 한 줄에 — 가격은 오른쪽 정렬(#59 04) */}
                <div className="flex items-baseline justify-between gap-4">
                  <label htmlFor={checkboxId} className="min-w-0 truncate text-base font-medium">
                    {product.name}
                    {current && (
                      <span className="text-xs font-normal text-muted-fg"> · {t.current}</span>
                    )}
                  </label>
                  <span className="shrink-0 text-muted-fg">
                    {formatMoney(product.salePrice)}
                    {product.saleStatus.code !== 'ON_SALE' && ` · ${product.saleStatus.name}`}
                  </span>
                </div>

                {product.customOrder && product.saleStatus.code === 'ON_SALE' ? (
                  <p className="text-xs text-muted-fg">
                    {t.customOrder}{' '}
                    {!current && (
                      <Link
                        href={`/products/${product.productId}`}
                        className="underline underline-offset-4"
                      >
                        {t.orderFromProduct}
                      </Link>
                    )}
                  </p>
                ) : (
                  pickable &&
                  included && (
                    <OptionGroupsField
                      groups={product.optionGroups}
                      skus={product.skus}
                      selection={selection}
                      namePrefix={`${checkboxId}-`}
                      size="sm"
                      onSelect={(groupId, valueId) =>
                        update(product.productId, (pick) => ({
                          ...pick,
                          selection: selectOption(product.skus, pick.selection, groupId, valueId),
                        }))
                      }
                    />
                  )
                )}

                {!current && (
                  <Link
                    href={`/products/${product.productId}`}
                    className="self-start text-xs text-muted-fg underline-offset-4 hover:text-fg hover:underline"
                  >
                    {t.viewProduct} <span aria-hidden>→</span>
                  </Link>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      <div
        className={cx(
          'flex flex-col gap-3',
          floatingAction &&
            'fixed inset-x-0 bottom-0 z-30 bg-bg px-4 py-3 transition lg:static lg:bg-transparent lg:p-0',
          floatingAction &&
            !inView &&
            'pointer-events-none translate-y-full opacity-0 lg:pointer-events-auto lg:translate-y-0 lg:opacity-100',
        )}
      >
        {/* 장바구니 API(#26)가 생기면 lines 를 담는다 */}
        <Button disabled variant="secondary" className="h-12 w-full" aria-describedby={noticeId}>
          {t.addAll}
          {lines.length > 0 && ` (${lines.length})`}
        </Button>
        <p id={noticeId} role="status" className="text-center text-xs text-muted-fg">
          {notice}
        </p>
      </div>
    </div>
  );
}
