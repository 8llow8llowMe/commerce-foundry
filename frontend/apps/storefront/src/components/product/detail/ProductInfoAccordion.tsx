import type { ProductInfo } from '@cf/ui';
import type { ReactNode } from 'react';
import type { Dictionary } from '@/lib/i18n/dictionaries';

/**
 * 상품 상세 구매 정보 아래 공통 안내 아코디언(#60). 사이즈 가이드 · 배송 · 교환은 상점 공통이라 브랜드 설정
 * `content[locale].productInfo` 에서 받고, 값이 없는 항목은 그리지 않는다(고객사 값 받기 전). 상품마다 다른
 * 소재 · 관리 · 사이즈 · 핏은 상품 데이터가 생기면 같은 모양으로 더한다.
 *
 * 네이티브 <details> 라 스크립트 없이 열고 닫히고, 키보드 · 화면 읽기 동작을 브라우저가 맡는다.
 * 선 없이 여백으로 나눈다(#57 금지 목록). 표는 진짜 <table> 이고 선을 써도 된다(#60).
 */
export function ProductInfoAccordion({
  info,
  labels,
}: {
  info: ProductInfo;
  labels: Dictionary['product']['info'];
}) {
  const { sizeGuide, shipping } = info;
  if (!sizeGuide && !shipping) return null;

  return (
    <div className="mt-4 flex flex-col">
      {sizeGuide && (
        <Item title={labels.sizeGuide}>
          <div className="relative overflow-x-auto">
            <table className="w-full text-left text-xs whitespace-nowrap">
              <thead>
                <tr>
                  {sizeGuide.columns.map((column) => (
                    <th
                      key={column}
                      scope="col"
                      className="border-b border-border py-2 pr-4 font-normal text-muted-fg"
                    >
                      {column}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {sizeGuide.rows.map(([size, ...values], index) => (
                  <tr key={`${size}-${index}`}>
                    <th scope="row" className="border-b border-border py-2 pr-4 font-semibold">
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
        </Item>
      )}

      {shipping && (
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
