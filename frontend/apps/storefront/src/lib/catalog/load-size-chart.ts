import { getI18n } from '@/lib/i18n/server';
import { serverFetch } from '@/lib/server-api';
import {
  sizeChartPath,
  sizeChartUnit,
  sizeChartView,
  type ProductSizeChart,
  type SizeChartView,
} from './size-chart';

/**
 * 상품 사이즈표를 아코디언 표로. 표가 없거나(null) 조회가 실패하면 null — 그때 화면은 기존 표시
 * (브랜드 공통 사이즈 가이드 · 상세 설명 속 사이즈 이미지)를 그대로 둔다. 상품 화면을 막지 않는다.
 */
export async function loadSizeChart(productId: string): Promise<SizeChartView | null> {
  if (!/^\d{1,19}$/.test(productId)) return null;
  const { locale, dict } = await getI18n();
  const chart = await serverFetch<ProductSizeChart | null>(
    sizeChartPath(productId, sizeChartUnit(locale)),
  ).catch(() => null);
  return chart && chart.rows.length > 0 && chart.columns.length > 0
    ? sizeChartView(chart, locale, dict.product.sizeChart)
    : null;
}
