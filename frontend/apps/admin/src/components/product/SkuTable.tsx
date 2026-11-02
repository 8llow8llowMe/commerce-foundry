'use client';

import { cx, FieldError } from '@cf/ui';
import { useId, type InputHTMLAttributes } from 'react';
import {
  SALE_STATUS_LABELS,
  skuLabel,
  type OptionGroupForm,
  type SaleStatusCode,
  type SkuForm,
  type SkuRow,
} from '@/lib/product/create';

const cellInput =
  'h-9 w-full rounded-md border bg-bg px-2 text-fg outline-none focus:ring-2 focus:ring-primary';

/**
 * 옵션 조합(SKU) 표 — 줄은 옵션의 조합이고 순서는 그룹 · 값 입력 순서다. 추가금은 판매가에 더해지는 금액이고 화면은 합계를 계산하지 않는다.
 * 칸 오류 키는 `skus.<줄 번호>.<칸>` 이다(보내는 순서 = 이 표의 순서).
 */
export function SkuTable({
  rows,
  groups,
  currencyUnit,
  errorsOf,
  onChange,
}: {
  rows: SkuRow[];
  groups: OptionGroupForm[];
  /** 금액 칸 단위 안내("원" · "달러") */
  currencyUnit: string;
  errorsOf: (key: string) => string[];
  onChange: (key: string, sku: SkuForm) => void;
}) {
  return (
    <div className="flex flex-col gap-2">
      <ul className="list-disc pl-5 text-xs text-muted-fg">
        <li>
          판매중 SKU 중 하나는 추가금이 0 이어야 합니다 — 판매가가 판매중 SKU 의 가장 낮은
          가격입니다.
        </li>
        <li>
          처음 재고를 비우면 재고 없이(품절로) 등록됩니다. 재고는 등록 뒤 재고 화면에서도 넣을 수
          있습니다.
        </li>
        <li>무게 · HS 코드는 해외 판매 상품에 필요합니다.</li>
      </ul>
      {/* sr-only 같은 absolute 요소가 스크롤 상자 밖으로 나가지 않게 relative 를 준다 */}
      <div className="relative overflow-x-auto">
        <table className="w-full min-w-[64rem] text-left text-sm">
          <caption className="sr-only">옵션 조합(SKU)</caption>
          <thead className="text-muted-fg">
            <tr>
              <th scope="col" className="py-2 pr-3 font-medium">
                조합
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                추가금 ({currencyUnit})
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                판매 상태
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                무게(g)
              </th>
              <th scope="col" className="py-2 pr-3 font-medium">
                HS 코드
              </th>
              <th scope="col" className="py-2 font-medium">
                처음 재고
              </th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <SkuLine
                key={row.key}
                row={row}
                label={skuLabel(groups, row.values)}
                errorsOf={(field) => errorsOf(`skus.${index}.${field}`)}
                onChange={(sku) => onChange(row.key, sku)}
              />
            ))}
          </tbody>
        </table>
      </div>
      <FieldError messages={errorsOf('skus')} />
    </div>
  );
}

function SkuLine({
  row,
  label,
  errorsOf,
  onChange,
}: {
  row: SkuRow;
  label: string;
  errorsOf: (field: string) => string[];
  onChange: (sku: SkuForm) => void;
}) {
  const { sku } = row;
  const set = (patch: Partial<SkuForm>) => onChange({ ...sku, ...patch });
  return (
    <tr className="border-t border-border align-top">
      <th scope="row" className="py-2 pr-3 font-normal">
        {label}
      </th>
      <td className="w-36 py-2 pr-3">
        <Cell
          label={`${label} 추가금`}
          inputMode="decimal"
          value={sku.additionalPrice}
          errors={errorsOf('additionalPrice')}
          onChange={(event) => set({ additionalPrice: event.currentTarget.value })}
        />
      </td>
      <td className="w-32 py-2 pr-3">
        <select
          aria-label={`${label} 판매 상태`}
          value={sku.saleStatus}
          onChange={(event) => set({ saleStatus: event.currentTarget.value as SaleStatusCode })}
          className="h-9 rounded-md border border-border bg-bg px-2"
        >
          {(Object.keys(SALE_STATUS_LABELS) as SaleStatusCode[]).map((code) => (
            <option key={code} value={code}>
              {SALE_STATUS_LABELS[code]}
            </option>
          ))}
        </select>
        <FieldError messages={errorsOf('saleStatus')} />
      </td>
      <td className="w-28 py-2 pr-3">
        <Cell
          label={`${label} 무게(g)`}
          inputMode="numeric"
          value={sku.weightGrams}
          errors={errorsOf('weightGrams')}
          onChange={(event) => set({ weightGrams: event.currentTarget.value })}
        />
      </td>
      <td className="w-36 py-2 pr-3">
        <Cell
          label={`${label} HS 코드`}
          value={sku.hsCode}
          maxLength={20}
          placeholder="예: 6211.42"
          errors={errorsOf('hsCode')}
          onChange={(event) => set({ hsCode: event.currentTarget.value })}
        />
      </td>
      <td className="w-56 py-2">
        <div className="flex gap-2">
          <select
            aria-label={`${label} 재고 관리`}
            value={sku.stockMode}
            onChange={(event) =>
              set({ stockMode: event.currentTarget.value as SkuForm['stockMode'] })
            }
            className="h-9 rounded-md border border-border bg-bg px-2"
          >
            <option value="quantity">수량</option>
            <option value="unlimited">무제한</option>
          </select>
          {sku.stockMode === 'quantity' && (
            <Cell
              label={`${label} 처음 재고 수량`}
              inputMode="numeric"
              value={sku.quantity}
              placeholder="비우면 품절"
              errors={errorsOf('initialStock')}
              onChange={(event) => set({ quantity: event.currentTarget.value })}
            />
          )}
        </div>
        {sku.stockMode === 'unlimited' && (
          <p className="mt-1 text-xs text-muted-fg">재고를 세지 않고 판매합니다.</p>
        )}
        {sku.stockMode === 'quantity' && sku.quantity.trim() === '' && (
          <p className="mt-1 text-xs text-muted-fg">품절로 등록됩니다.</p>
        )}
        {sku.stockMode === 'unlimited' && <FieldError messages={errorsOf('initialStock')} />}
      </td>
    </tr>
  );
}

/** 표 안 입력 한 칸. 줄마다 보이는 이름이 없어 aria-label 로 이름을 준다 */
function Cell({
  label,
  errors,
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, 'id' | 'aria-label'> & {
  label: string;
  errors: string[];
}) {
  const id = useId();
  const invalid = errors.length > 0;
  return (
    <div className="flex flex-col">
      <input
        aria-label={label}
        aria-invalid={invalid || undefined}
        aria-describedby={invalid ? `${id}-error` : undefined}
        className={cx(cellInput, invalid ? 'border-danger' : 'border-border')}
        {...rest}
      />
      <FieldError id={`${id}-error`} messages={errors} />
    </div>
  );
}
