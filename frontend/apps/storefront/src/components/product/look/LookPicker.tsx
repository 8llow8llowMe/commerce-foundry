'use client';

import { formatMoney } from '@cf/api-client';
import { Button } from '@cf/ui';
import { Link } from '@/components/i18n/Link';
import { useId, useState } from 'react';
import { OptionGroupsField } from '@/components/product/detail/OptionGroupsField';
import {
  lookCartLines,
  lookPickable,
  type LookPick,
  type LookProduct,
} from '@/lib/catalog/look-cart';
import { selectOption } from '@/lib/catalog/option-selection';

/**
 * Shop the Look 구성품 목록 — 메인 04 영역과 상품 상세가 함께 쓴다.
 * 상품마다 함께 담기 여부와 옵션을 고르고, lookCartLines 로 담을 SKU 를 정한다.
 * 장바구니 연결은 #26 이다 — 지금은 버튼을 막고 무엇이 남았는지만 알려 준다.
 * 합계는 보이지 않는다(금액은 서버 값만 — 장바구니가 계산한다).
 */
export function LookPicker({
  products,
  currentProductId,
}: {
  products: LookProduct[];
  /** 상품 상세에서 지금 보는 상품 */
  currentProductId?: string;
}) {
  const baseId = useId();
  const noticeId = useId();
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
      ? `${incomplete.join(', ')} 옵션을 선택해 주세요.`
      : lines.length === 0
        ? '함께 담을 상품을 골라 주세요.'
        : '장바구니 담기는 준비 중입니다.';

  return (
    <div className="flex flex-col gap-6">
      <ul className="flex flex-col border-t border-border">
        {picks.map(({ product, included, selection }, index) => {
          const pickable = lookPickable(product);
          const checkboxId = `${baseId}-${index}`;
          const current = product.productId === currentProductId;
          return (
            <li key={product.productId} className="flex gap-4 border-b border-border py-4">
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
              <div className="aspect-[3/4] w-16 shrink-0 self-start bg-muted">
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
                <div className="flex flex-col gap-1">
                  <label htmlFor={checkboxId} className="truncate">
                    {product.name}
                    {current && <span className="text-xs text-muted-fg"> · 지금 보는 상품</span>}
                  </label>
                  <span className="text-muted-fg">
                    {formatMoney(product.salePrice)}
                    {product.saleStatus.code !== 'ON_SALE' && ` · ${product.saleStatus.name}`}
                  </span>
                </div>

                {product.customOrder && product.saleStatus.code === 'ON_SALE' ? (
                  <p className="text-xs text-muted-fg">
                    맞춤 제작 상품은 치수를 받아야 해요.{' '}
                    {!current && (
                      <Link
                        href={`/products/${product.productId}`}
                        className="underline underline-offset-4"
                      >
                        상품에서 주문하기
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
                    상품 보기 <span aria-hidden>→</span>
                  </Link>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      <div className="flex flex-col gap-3">
        {/* 장바구니 API(#26)가 생기면 lines 를 담는다 */}
        <Button disabled variant="secondary" className="h-12 w-full" aria-describedby={noticeId}>
          함께 담기{lines.length > 0 && ` (${lines.length})`}
        </Button>
        <p id={noticeId} role="status" className="text-center text-xs text-muted-fg">
          {notice}
        </p>
      </div>
    </div>
  );
}
