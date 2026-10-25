import type { ProductDetail } from './types';

/**
 * 모바일 하단 바 구매하기가 여는 곳(#60). 옵션 · 수량 · 담기는 바텀시트에서 고른다.
 * 맞춤 제작 상품은 입력칸이 길어, 판매중이 아닌 상품은 담을 수 없어 상태 안내가 있는 구매 패널로 스크롤한다.
 */
export function purchaseTarget(
  product: Pick<ProductDetail, 'customOrder' | 'saleStatus'>,
): 'sheet' | 'panel' {
  if (product.customOrder || product.saleStatus.code !== 'ON_SALE') return 'panel';
  return 'sheet';
}
