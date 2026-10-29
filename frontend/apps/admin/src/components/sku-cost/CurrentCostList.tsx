import { formatMoney } from '@cf/api-client';
import { formatDateTime } from '@/lib/display/text';
import { currentCostRows } from '@/lib/sku-cost/form';
import { COST_TYPE_LABELS, type SkuCurrentCosts } from '@/lib/sku-cost/types';

/**
 * 지금 적용되는 유형별 원가(#395). 응답에 없는 유형은 "미입력" 이다 — 0원(입력한 값)과 다르게 보인다.
 * 금액은 서버 값을 `formatMoney` 로 옮겨 적기만 한다.
 */
export function CurrentCostList({ current }: { current: SkuCurrentCosts }) {
  return (
    <dl className="grid gap-3 sm:grid-cols-2">
      {currentCostRows(current).map(({ costType, item }) => (
        <div key={costType} className="flex flex-col gap-1 rounded-md bg-bg p-4">
          <dt className="text-sm text-muted-fg">
            {item?.costType.name ?? COST_TYPE_LABELS[costType]}
          </dt>
          {item ? (
            <dd className="flex flex-col gap-0.5">
              <span className="text-lg font-semibold tabular-nums">{formatMoney(item.amount)}</span>
              <span className="text-xs text-muted-fg">
                {formatDateTime(item.effectiveFrom)}부터 · {item.reason}
              </span>
            </dd>
          ) : (
            <dd className="flex flex-col gap-0.5">
              <span className="text-lg font-semibold text-muted-fg">미입력</span>
              <span className="text-xs text-muted-fg">
                아직 입력하지 않았습니다(0원과 다릅니다).
              </span>
            </dd>
          )}
        </div>
      ))}
    </dl>
  );
}
