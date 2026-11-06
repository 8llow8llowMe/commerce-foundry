'use client';

import { cx } from '@cf/ui';
import { useId, useState } from 'react';
import type { SizeChartSet } from '@/lib/catalog/size-chart';
import { SizeTable } from './SizeTable';

/**
 * 상품 사이즈표(#218)를 cm · inch 로 바꿔 보는 표. 두 단위 값은 서버가 바꿔 함께 내려 주고, 여기서는 고르기만 한다.
 * 처음 단위는 언어 기본(국문 cm, 영문 inch)이라 스크립트 전에도 그 표가 서버 HTML 에 있다.
 * 받은 단위가 하나면 전환 버튼 없이 그 표만 그린다.
 */
export function SizeChartUnits({
  chart,
  unitLabel,
  measuresLabel,
}: {
  chart: SizeChartSet;
  /** 단위 고르기 묶음 이름(화면 읽기용) */
  unitLabel: string;
  measuresLabel: string;
}) {
  const name = useId();
  const [unit, setUnit] = useState(chart.initial);
  const current = chart.views.find((entry) => entry.unit === unit) ?? chart.views[0];
  if (!current) return null;

  return (
    <>
      {chart.views.length > 1 && (
        <fieldset className="mb-3 flex justify-end">
          <legend className="sr-only">{unitLabel}</legend>
          <div className="flex">
            {chart.views.map((entry) => {
              const checked = entry.unit === current.unit;
              return (
                <label
                  key={entry.unit}
                  className={cx(
                    'inline-flex min-h-8 min-w-12 cursor-pointer items-center justify-center border px-3 text-xs transition',
                    'has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-primary',
                    checked ? 'border-fg bg-fg text-bg' : 'border-border hover:border-fg',
                  )}
                >
                  <input
                    type="radio"
                    name={name}
                    value={entry.unit}
                    checked={checked}
                    onChange={() => setUnit(entry.unit)}
                    className="sr-only"
                  />
                  {entry.label}
                </label>
              );
            })}
          </div>
        </fieldset>
      )}
      <SizeTable
        guide={current.view}
        measures={current.view.measures}
        measuresLabel={measuresLabel}
      />
    </>
  );
}
