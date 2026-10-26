import { cx, type ProductInfo } from '@cf/ui';
import type { ReactNode } from 'react';
import type { SizeChartView } from '@/lib/catalog/size-chart';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 상품 상세 구매 정보 아래 공통 안내 아코디언(#60). 사이즈 가이드 · 배송 · 교환은 상점 공통이라 브랜드 설정
 * `content[locale].productInfo` 에서 받고, 값이 없는 항목은 그리지 않는다(고객사 값 받기 전).
 * 배송비 줄(`shippingFee`)은 delivery 정책 API 값이다(#171) — 배송 · 교환 항목 맨 위에 두고, 브랜드 문단이 없어도 그린다. 상품마다 다른
 * 소재 · 관리 · 핏은 상품 데이터가 생기면 같은 모양으로 더한다.
 * 상품 사이즈표(`sizeChart`, #218)가 있으면 브랜드 공통 사이즈 가이드 대신 그것을 그린다. 없으면(이미지 사이즈표만 있는
 * 상품 포함) 브랜드 가이드 · 상세 설명 속 이미지가 그대로다.
 *
 * 사이즈 표는 좁은 화면에서 가로로 스크롤하고, 첫 열(사이즈 이름)은 왼쪽에 붙어 있다(sticky — 배경을 깔아 겹친 칸을 가린다).
 *
 * 네이티브 <details> 라 스크립트 없이 열고 닫히고, 키보드 · 화면 읽기 동작을 브라우저가 맡는다.
 * 선 없이 여백으로 나눈다(#57 금지 목록). 표는 진짜 <table> 이고 선을 써도 된다(#60).
 */
export function ProductInfoAccordion({
  info,
  shippingFee = [],
  sizeChart = null,
  labels,
}: {
  info: ProductInfo;
  /** 상품 사이즈표. 없으면 null */
  sizeChart?: SizeChartView | null;
  /** 배송비 정책 안내 줄. 정책이 없으면 빈 배열 */
  shippingFee?: readonly string[];
  labels: Dictionary['product']['info'];
}) {
  const sizeGuide = sizeChart ?? info.sizeGuide;
  const measures = sizeChart?.measures ?? [];
  const shipping = [...shippingFee, ...(info.shipping ?? [])];
  if (!sizeGuide && shipping.length === 0) return null;

  return (
    <div className="mt-4 flex flex-col">
      {sizeGuide && (
        <Item title={labels.sizeGuide}>
          <div className="relative overflow-x-auto">
            <table className="w-full text-left text-xs whitespace-nowrap">
              <thead>
                <tr>
                  {sizeGuide.columns.map((column, index) => (
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
                {sizeGuide.rows.map(([size, ...values], index) => (
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
          {sizeGuide.note && <p className="mt-3 text-xs text-muted-fg">{sizeGuide.note}</p>}
          {measures.length > 0 && (
            <div className="mt-4 text-xs text-muted-fg">
              <p className="font-medium text-fg">{labels.measures}</p>
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
        </Item>
      )}

      {shipping.length > 0 && (
        <Item title={labels.shipping}>
          <div className="flex flex-col gap-3 text-sm leading-7">
            {shipping.map((paragraph, index) => (
              <p key={index}>{paragraph}</p>
            ))}
          </div>
        </Item>
      )}
    </div>
  );
}

/** 사이즈 표 첫 열 — 가로 스크롤해도 왼쪽에 남는다 */
const STICKY = 'sticky left-0 bg-bg';

function Item({ title, children }: { title: string; children: ReactNode }) {
  return (
    <details className="group">
      <summary className="flex cursor-pointer list-none items-center justify-between py-4 text-sm tracking-[0.1em] [&::-webkit-details-marker]:hidden">
        {title}
        {/* 열리면 + 가 45도 돌아 × 가 된다 */}
        <span
          aria-hidden
          className="text-lg leading-none transition group-open:rotate-45 motion-reduce:transition-none"
        >
          +
        </span>
      </summary>
      <div className="pb-6">{children}</div>
    </details>
  );
}
