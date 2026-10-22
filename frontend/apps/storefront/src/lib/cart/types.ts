import type { Money } from '@cf/api-client';
import type { SaleStatus } from '@/lib/catalog/types';

// order-service 회원 장바구니 API 계약 (`/api/v1/carts/me/**`, BE #12). 정본은 BE DTO(@Schema)다.
// 가격 · 상품명은 저장하지 않고 조회할 때마다 현재 SKU 값으로 내려온다 — 합계도 서버 값만 쓴다.

/** 회원 장바구니 경로 — 장바구니 화면과 주문서가 같이 읽는다 */
export const CART_PATH = '/api/v1/carts/me';

export type CustomOption = { fieldKey: string; value: string };

export type CartLine = {
  itemId: string;
  skuId: string;
  productId: string;
  productName: string;
  optionName: string | null;
  quantity: number;
  unitPrice: Money;
  lineAmount: Money;
  /** ON_SALE(구매 가능) · SOLD_OUT · STOP_SELLING — 화면은 name 을 그대로 쓴다 */
  status: SaleStatus;
  /** null 이면 99 까지 */
  maxQuantityPerOrder: number | null;
  customOrder: boolean;
  customOptions: CustomOption[];
  /** 담은 뒤 상한이 줄어 지금 수량이 상한을 넘는다 — 합계에서 빠진다 */
  quantityExceeded: boolean;
};

export type CartResponse = {
  /** 최근에 담은 순 */
  items: CartLine[];
  /** 구매 가능하고 수량이 상한 안인 줄만 더한 합계. 그런 줄이 없으면 null */
  totalAmount: Money | null;
  purchasableCount: number;
};

/** POST /items · /merge 항목. skuId 는 JS 안전 정수를 넘을 수 있어 문자열 그대로 보낸다(Jackson 이 Long 으로 읽는다) */
export type CartItemRequest = {
  skuId: string;
  quantity: number;
  customOptions?: CustomOption[];
};

export type CartMergeSkipReason =
  | 'SKU_NOT_FOUND'
  | 'NOT_PURCHASABLE'
  | 'CUSTOM_OPTION_NOT_ALLOWED'
  | 'LINE_LIMIT_EXCEEDED'
  | 'QUANTITY_LIMIT_EXCEEDED';

export type CartMergeResponse = {
  cart: CartResponse;
  skippedItems: { index: number; skuId: string; reason: CartMergeSkipReason }[];
};

/** 줄 수 · 줄당 수량 상한 (BE 와 같다) */
export const CART_MAX_LINES = 50;
export const CART_MAX_QUANTITY = 99;
