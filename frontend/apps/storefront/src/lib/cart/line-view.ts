import type { Money } from '@cf/api-client';
import type { Preorder, ProductDetail, SaleStatus } from '@/lib/catalog/types';
import type { LocalCartLine } from './local-cart';
import { lineKey } from './local-cart';
import { CART_MAX_QUANTITY, type CartLine } from './types';

/** 장바구니 한 줄의 화면 모양 — 회원(서버 줄)과 비회원(로컬 줄 + 상품 상세)이 같은 모양으로 그린다 */
export type CartLineView = {
  key: string;
  productId: string;
  /** null 이면 판매하지 않는(찾을 수 없는) 상품 */
  name: string | null;
  /** 옵션 값을 ' / ' 로 이은 것(BE 와 같은 모양). 옵션이 없으면 빈 문자열 */
  optionName: string | null;
  quantity: number;
  maxQuantity: number;
  unitPrice: Money | null;
  /** 서버가 계산한 줄 금액 — 비회원 줄은 계산하지 않아 null 이다 */
  lineAmount: Money | null;
  /** null 이면 상품을 찾을 수 없다 */
  status: SaleStatus | null;
  /** 상한이 줄어 지금 수량이 상한을 넘는다 */
  quantityExceeded: boolean;
  customOptions: { label: string; value: string }[];
  /**
   * 프리오더 안내(발송 예정일). 비회원 줄은 상품 상세에서 알고, 회원 줄은 장바구니 응답에 프리오더 정보가 없어
   * 언제나 null 이다(BE 장바구니 계약에 생기면 채운다)
   */
  preorder: Preorder | null;
};

export function memberLineView(line: CartLine): CartLineView {
  const maxQuantity = Math.min(line.maxQuantityPerOrder ?? CART_MAX_QUANTITY, CART_MAX_QUANTITY);
  return {
    key: line.itemId,
    productId: line.productId,
    name: line.productName,
    optionName: line.optionName,
    quantity: line.quantity,
    maxQuantity,
    unitPrice: line.unitPrice,
    lineAmount: line.lineAmount,
    status: line.status,
    quantityExceeded: line.quantityExceeded,
    preorder: null,
    // 회원 줄에는 입력 항목 이름이 없다 — 키를 그대로 보인다(BE 응답에 label 이 생기면 바꾼다)
    customOptions: line.customOptions.map((option) => ({
      label: option.fieldKey,
      value: option.value,
    })),
  };
}

/**
 * 비회원 줄 + 그 상품의 현재 상세(없거나 숨겨졌으면 null)로 화면 줄을 만든다.
 * 상태 판정은 BE 장바구니와 같다 — SKU 가 없으면 판매 중지, 상품이 판매중이 아니면 상품 상태, 아니면 SKU 상태.
 */
export function guestLineView(line: LocalCartLine, product: ProductDetail | null): CartLineView {
  const base = {
    key: lineKey(line.skuId, line.customOptions),
    productId: line.productId,
    quantity: line.quantity,
    lineAmount: null,
    preorder: product?.preorder ?? null,
  };
  const sku = product?.skus.find((item) => item.skuId === line.skuId);
  if (!product || !sku) {
    return {
      ...base,
      name: product?.name ?? null,
      optionName: null,
      maxQuantity: CART_MAX_QUANTITY,
      unitPrice: null,
      status: null,
      quantityExceeded: false,
      customOptions: line.customOptions.map((option) => ({
        label: option.fieldKey,
        value: option.value,
      })),
    };
  }

  const maxQuantity = Math.min(product.maxQuantityPerOrder ?? CART_MAX_QUANTITY, CART_MAX_QUANTITY);
  const valueNames = new Map(
    product.optionGroups.flatMap((group) =>
      group.values.map((value) => [value.optionValueId, value.name] as const),
    ),
  );
  const labels = new Map(
    (product.customOrder?.fields ?? []).map((field) => [field.fieldKey, field.label] as const),
  );
  return {
    ...base,
    name: product.name,
    optionName: sku.optionValueIds.map((id) => valueNames.get(id) ?? '').join(' / '),
    maxQuantity,
    unitPrice: sku.salePrice,
    status: product.saleStatus.code === 'ON_SALE' ? sku.saleStatus : product.saleStatus,
    quantityExceeded: line.quantity > maxQuantity,
    customOptions: line.customOptions.map((option) => ({
      label: labels.get(option.fieldKey) ?? option.fieldKey,
      value: option.value,
    })),
  };
}
