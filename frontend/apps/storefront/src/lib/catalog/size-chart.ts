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

/** 고객이 고를 수 있는 단위 — 전환 버튼 순서다 */
export const SIZE_CHART_UNITS: readonly SizeChartUnit[] = ['CM', 'INCH'];

/**
 * 언어별로 처음 보일 단위. 영문 스토어는 inch, 국문은 cm 이다(#218). 값은 두 단위 모두 서버가 바꿔 준다
 * (1 in = 2.54 cm, 소수 둘째 자리 반올림 — 화면은 계산하지 않는다). 고객은 전환 버튼으로 다른 단위를 본다.
 */
const UNIT_BY_LOCALE: Record<Locale, SizeChartUnit> = { ko: 'CM', en: 'INCH' };

export function sizeChartUnit(locale: Locale): SizeChartUnit {
  return UNIT_BY_LOCALE[locale];
}

export function sizeChartPath(productId: string, unit: SizeChartUnit | null): string {
  const path = `/api/v1/products/${encodeURIComponent(productId)}/size-chart`;
  return unit ? `${path}?unit=${unit}` : path;
}

/** 표 아래 측정 방법(항목 설명이 있는 것만) */
export type SizeMeasure = { name: string; description: string };

export type SizeChartView = SizeGuide & { measures: SizeMeasure[] };

/**
 * 상품 상세에 넘기는 사이즈표 — 받은 단위마다 표 하나. 한 단위만 받았으면(다른 단위 조회 실패) 전환 버튼 없이 그 표다.
 * `initial` 은 언어 기본 단위이고, 그 단위를 못 받았으면 받은 첫 단위다.
 */
export type SizeChartSet = {
  initial: SizeChartUnit;
  views: { unit: SizeChartUnit; label: string; view: SizeChartView }[];
};

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

/**
 * 단위별 응답 → 상품 상세 사이즈표. 표가 없거나(null · 빈 행 · 빈 항목) 조회가 실패한 단위는 뺀다.
 * 남은 단위가 없으면 null — 화면은 기존 표시(브랜드 공통 사이즈 가이드 · 상세 설명 속 이미지)를 둔다.
 */
export function sizeChartSet(
  charts: Partial<Record<SizeChartUnit, ProductSizeChart | null>>,
  locale: Locale,
  labels: Dictionary['product']['sizeChart'],
): SizeChartSet | null {
  const views = SIZE_CHART_UNITS.flatMap((unit) => {
    const chart = charts[unit];
    return chart && chart.rows.length > 0 && chart.columns.length > 0
      ? [{ unit, label: labels.units[unit], view: sizeChartView(chart, locale, labels) }]
      : [];
  });
  const [first] = views;
  if (!first) return null;
  const preferred = sizeChartUnit(locale);
  return {
    initial: views.some((entry) => entry.unit === preferred) ? preferred : first.unit,
    views,
  };
}
