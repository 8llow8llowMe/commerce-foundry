import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';
import {
  SIZE_CHART_UNITS,
  sizeChartPath,
  sizeChartSet,
  type ProductSizeChart,
  type SizeChartSet,
} from './size-chart';

/**
 * 상품 사이즈표를 cm · inch 두 단위로 함께 읽는다 — 단위 전환은 서버 값끼리 바꾸고 화면은 계산하지 않는다.
 * 단위마다 실패를 따로 삼켜 한 단위라도 받으면 그 표를 보인다. 표가 없거나 둘 다 실패하면 null 이고
 * 상품 화면을 막지 않는다(기존 브랜드 가이드 · 상세 설명 속 이미지가 그대로다).
 */
export async function loadSizeChart(productId: string): Promise<SizeChartSet | null> {
  if (!/^\d{1,19}$/.test(productId)) return null;
  const { locale, dict } = await getI18n();
  const charts = await Promise.all(
    SIZE_CHART_UNITS.map((unit) =>
      serverFetch<ProductSizeChart | null>(sizeChartPath(productId, unit)).catch(() => null),
    ),
  );
  return sizeChartSet(
    Object.fromEntries(SIZE_CHART_UNITS.map((unit, index) => [unit, charts[index]])),
    locale,
    dict.product.sizeChart,
  );
}
