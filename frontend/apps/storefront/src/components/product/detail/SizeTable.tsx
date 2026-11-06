import { cx, type SizeGuide } from '@cf/ui';
import type { SizeMeasure } from '@/lib/catalog/size-chart';

/**
 * 사이즈 표 하나 + 안내 줄 + 재는 방법. 브랜드 공통 사이즈 가이드와 상품 사이즈표(#218)가 같이 쓴다.
 * 좁은 화면에서는 표만 가로로 스크롤하고, 첫 열(사이즈 이름)은 왼쪽에 붙어 있다(sticky — 배경을 깔아 겹친 칸을 가린다).
 * 훅이 없어 서버 · 클라이언트 컴포넌트 어디서나 쓴다.
 */
export function SizeTable({
  guide,
  measures = [],
  measuresLabel,
}: {
  guide: SizeGuide;
  measures?: readonly SizeMeasure[];
  measuresLabel: string;
}) {
  return (
    <>
      <div className="relative overflow-x-auto">
        <table className="w-full text-left text-xs whitespace-nowrap">
          <thead>
            <tr>
              {guide.columns.map((column, index) => (
                <th
                  key={`${column}-${index}`}
                  scope="col"
                  className={cx(
                    'border-b border-border py-2 pr-4 font-normal text-muted-fg',
                    index === 0 && STICKY,
                  )}
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {guide.rows.map(([size, ...values], index) => (
              <tr key={`${size}-${index}`}>
                <th
                  scope="row"
                  className={cx('border-b border-border py-2 pr-4 font-semibold', STICKY)}
                >
                  {size}
                </th>
                {values.map((value, column) => (
                  <td key={column} className="border-b border-border py-2 pr-4">
                    {value}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {guide.note && <p className="mt-3 text-xs text-muted-fg">{guide.note}</p>}
      {measures.length > 0 && (
        <div className="mt-4 text-xs text-muted-fg">
          <p className="font-medium text-fg">{measuresLabel}</p>
          <dl className="mt-2 flex flex-col gap-1">
            {measures.map((measure) => (
              <div key={measure.name} className="flex gap-2">
                <dt className="shrink-0 font-medium">{measure.name}</dt>
                <dd>{measure.description}</dd>
              </div>
            ))}
          </dl>
        </div>
      )}
    </>
  );
}

/** 사이즈 표 첫 열 — 가로 스크롤해도 왼쪽에 남는다 */
const STICKY = 'sticky left-0 bg-bg';
