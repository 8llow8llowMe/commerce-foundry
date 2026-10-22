import type { Money } from '@cf/api-client';

/**
 * 어드민 재고 화면이 쓰는 API 계약.
 * - 재고: product-service `StockAdminController`(#10 · #86) — `/api/v1/admin/stocks`
 * - 상품 고르기: 어드민 상품 목록 `GET /api/v1/admin/products`(#9, 검색어 · 숨김 포함)
 * - 옵션 · SKU: 고객 상품 상세 `GET /api/v1/products/{id}`. 어드민 상품 상세 API 가 아직 없어서다
 *   — 고객 상세는 숨긴 상품에 404 라 숨긴 상품의 SKU 재고는 아직 볼 수 없다(어드민 상세가 생기면 바꾼다).
 */

export const STOCKS_PATH = '/api/v1/admin/stocks';
export { ADMIN_PRODUCTS_PATH } from '@/lib/product/query';
/** 재고 목록 조회 한 번에 넣을 수 있는 SKU 수(BE `@Size(max=100)`) */
export const MAX_SKU_IDS = 100;

export type StockAdmin = {
  skuId: string;
  /** false 면 무제한(수량을 세지 않는다) */
  stockManaged: boolean;
  /** 판매 가능 수량 — 결제 전 선점분이 이미 빠진 값 */
  availableQuantity: number;
  /** 결제 전 선점 수량(참고치) */
  reservedQuantity: number;
  /** 재고 행이 아직 없는 SKU 는 null(품절 · 관리 · 0 으로 본다) */
  updatedAt: string | null;
};

export type StockAdminList = { stocks: StockAdmin[] };

export const ADJUSTMENT_REASONS = [
  'RESTOCK',
  'RECOUNT',
  'DAMAGE',
  'MANAGEMENT_CHANGE',
  'OTHER',
] as const;
export type AdjustmentReason = (typeof ADJUSTMENT_REASONS)[number];

/** 사유 표시 이름 — BE `StockAdjustmentReason` 의 displayName 과 같다(이력 응답은 서버 이름을 쓴다) */
export const REASON_LABELS: Record<AdjustmentReason, string> = {
  RESTOCK: '입고',
  RECOUNT: '실사',
  DAMAGE: '파손 · 불량',
  MANAGEMENT_CHANGE: '관리 여부 변경',
  OTHER: '기타',
};

/** PATCH 본문. quantity 와 stockManaged 중 하나 이상(PRODUCT_110), quantity 를 보내면 expectedQuantity 필수(PRODUCT_111) */
export type StockAdjustmentRequest = {
  quantity?: number;
  expectedQuantity?: number;
  stockManaged?: boolean;
  /** 빈 값은 null 로 보내 서버 검증(PRODUCT_108)에 맡긴다 */
  reason: AdjustmentReason | null;
  memo: string | null;
};

export type StockAdjustmentHistoryItem = {
  id: string;
  beforeQuantity: number;
  afterQuantity: number;
  delta: number;
  beforeStockManaged: boolean;
  afterStockManaged: boolean;
  reason: { code: string; name: string; description: string };
  memo: string | null;
  adminMemberId: string;
  createdAt: string;
};

/** 어드민 상품 목록 항목 중 이 화면이 쓰는 칸만(`ProductListAdminItem`) */
export type ProductSummary = {
  productId: string;
  name: string;
  /** 스토어프론트 노출 여부 — false 면 고객 상세가 404 다 */
  visible: boolean;
  /** 재고를 반영한 판매 가능 상태(판매중 · 품절 · 판매 종료) */
  availability: { code: string; name: string };
  skuCount: number;
};

// 고객 상품 상세 중 이 화면이 쓰는 칸만

export type ProductForStock = {
  productId: string;
  name: string;
  optionGroups: {
    optionGroupId: string;
    name: string;
    values: { optionValueId: string; name: string }[];
  }[];
  skus: { skuId: string; optionValueIds: string[]; salePrice: Money }[];
};
