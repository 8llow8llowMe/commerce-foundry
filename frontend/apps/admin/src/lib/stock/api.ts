import { apiFetch } from '@cf/api-client';
import { MAX_SKU_IDS, STOCKS_PATH, type StockAdmin, type StockAdminList } from './types';

/**
 * SKU 재고를 읽는다. 한 번에 100개까지라 넘으면 나눠 부른다. 서버는 없는 SKU 를 빼고 돌려준다(404 아님).
 * 결과는 skuId 로 찾는다.
 */
export async function fetchStocks(skuIds: string[]): Promise<Map<string, StockAdmin>> {
  const chunks: string[][] = [];
  for (let i = 0; i < skuIds.length; i += MAX_SKU_IDS)
    chunks.push(skuIds.slice(i, i + MAX_SKU_IDS));
  const lists = await Promise.all(
    chunks.map((chunk) =>
      apiFetch<StockAdminList>(`${STOCKS_PATH}?skuIds=${chunk.map(encodeURIComponent).join(',')}`),
    ),
  );
  return new Map(lists.flatMap((list) => list.stocks).map((stock) => [stock.skuId, stock]));
}
