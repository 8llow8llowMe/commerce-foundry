import type { ProductInfo } from '@cf/ui';
import type { ReactNode } from 'react';
import type { SizeChartSet } from '@/lib/catalog/size-chart';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { SizeChartUnits } from './SizeChartUnits';
import { SizeTable } from './SizeTable';

/**
 * 상품 상세 구매 정보 아래 공통 안내 아코디언(#60). 사이즈 가이드 · 배송 · 교환은 상점 공통이라 브랜드 설정
 * `content[locale].productInfo` 에서 받고, 값이 없는 항목은 그리지 않는다(고객사 값 받기 전).
 * 배송비 줄(`shippingFee`)은 delivery 정책 API 값이다(#171) — 배송 · 교환 항목 맨 위에 두고, 브랜드 문단이 없어도 그린다. 상품마다 다른
 * 소재 · 관리 · 핏은 상품 데이터가 생기면 같은 모양으로 더한다.
 * 상품 사이즈표(`sizeChart`, #218)가 있으면 브랜드 공통 사이즈 가이드 대신 그것을 cm · inch 전환과 함께 그린다.
 * 없으면(이미지 사이즈표만 있는 상품 포함) 브랜드 가이드 · 상세 설명 속 이미지가 그대로다.
 *
 * 사이즈 표 모양(모바일 가로 스크롤 · 첫 열 sticky)은 `SizeTable` 하나가 맡는다.
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
  sizeChart?: SizeChartSet | null;
  /** 배송비 정책 안내 줄. 정책이 없으면 빈 배열 */
  shippingFee?: readonly string[];
  labels: Dictionary['product']['info'];
}) {
  const shipping = [...shippingFee, ...(info.shipping ?? [])];
  if (!sizeChart && !info.sizeGuide && shipping.length === 0) return null;

  return (
    <div className="mt-4 flex flex-col">
      {sizeChart ? (
        <Item title={labels.sizeGuide}>
          <SizeChartUnits
            chart={sizeChart}
            unitLabel={labels.unit}
            measuresLabel={labels.measures}
          />
        </Item>
      ) : (
        info.sizeGuide && (
          <Item title={labels.sizeGuide}>
            <SizeTable guide={info.sizeGuide} measuresLabel={labels.measures} />
          </Item>
        )
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
