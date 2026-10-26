import type { Locale, SizeGuide } from '@cf/ui';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { fill } from '@/lib/i18n/format';

/**
 * 고객 상품 사이즈표 — `GET /api/v1/products/{id}/size-chart?unit=`(BE #216, `ProductSizeChartWebController`).
 * 상품 상세 본문과 따로 읽는 선택 하위 리소스다. 표가 없으면 dataBody 가 null 이다.
 * 정본은 `backend/docs/api-design-guide.md` §2-10.
 */
export type ProductSizeChart = {
  productId: string;
  templateId: string;
  templateName: string;
  baseUnit: { code: string; name: string };
  /** 값의 단위. unit 을 빼고 부르면 저장 단위와 같다 */
  displayUnit: { code: string; name: string };
  note: string | null;
  columns: { columnId: string; name: string; description: string | null; displayOrder: number }[];
  rows: {
    sizeLabel: string;
    displayOrder: number;
    cells: { columnId: string; value: number }[];
  }[];
};

export type SizeChartUnit = 'CM' | 'INCH';

/**
 * 언어별로 받을 단위. null 이면 저장 단위 그대로다(서버가 바꾸지 않는다).
 * 영문 스토어(#31)는 아직 열지 않아 모든 언어가 저장 단위다 — 영문 스토어를 열 때 `en: 'INCH'` 로 바꾸면
 * 서버가 1 in = 2.54 cm, 소수 둘째 자리 반올림으로 바꿔 준다(화면은 계산하지 않는다).
 * cm · inch 를 고객이 고르는 전환 버튼은 그때 같은 API 의 `unit` 으로 붙인다.
 */
const UNIT_BY_LOCALE: Record<Locale, SizeChartUnit | null> = { ko: null, en: null };

export function sizeChartUnit(locale: Locale): SizeChartUnit | null {
  return UNIT_BY_LOCALE[locale];
}

export function sizeChartPath(productId: string, unit: SizeChartUnit | null): string {
  const path = `/api/v1/products/${encodeURIComponent(productId)}/size-chart`;
  return unit ? `${path}?unit=${unit}` : path;
}

/** 표 아래 측정 방법(항목 설명이 있는 것만) */
export type SizeMeasure = { name: string; description: string };

export type SizeChartView = SizeGuide & { measures: SizeMeasure[] };

const byOrder = <T extends { displayOrder: number }>(items: readonly T[]) =>
  [...items].sort((a, b) => a.displayOrder - b.displayOrder);

/**
 * API 사이즈표 → 아코디언 표(브랜드 공통 사이즈 가이드와 같은 모양). 값은 서버 값을 그 언어 숫자 표기로만 바꾼다.
 * 단위는 표 아래 안내 첫머리에 둔다. 칸이 빠진 줄은 빈 칸이다(서버가 막지만 화면이 깨지지 않게).
 */
export function sizeChartView(
  chart: ProductSizeChart,
  locale: Locale,
  labels: Dictionary['product']['sizeChart'],
): SizeChartView {
  const columns = byOrder(chart.columns);
  const number = new Intl.NumberFormat(locale, { maximumFractionDigits: 2 });
  const unit = labels.units[chart.displayUnit.code as SizeChartUnit] ?? chart.displayUnit.name;
  const unitNote = fill(labels.unit, { unit });
  return {
    columns: [labels.size, ...columns.map((column) => column.name)],
    rows: byOrder(chart.rows).map((row) => {
      const values = new Map(row.cells.map((cell) => [cell.columnId, cell.value]));
      return [
        row.sizeLabel,
        ...columns.map((column) => {
          const value = values.get(column.columnId);
          return value === undefined ? '' : number.format(value);
        }),
      ];
    }),
    note: chart.note ? `${unitNote} · ${chart.note}` : unitNote,
    measures: columns.flatMap((column) =>
      column.description ? [{ name: column.name, description: column.description }] : [],
    ),
  };
}
