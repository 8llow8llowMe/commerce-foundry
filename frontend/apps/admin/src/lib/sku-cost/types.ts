import type { Money } from '@cf/api-client';

/**
 * 어드민 SKU 원가 이력 계약(#395) — product-service `SkuCostAdminController`(BE #361), 정본은 `backend/docs/api-design-guide.md` §2-12.
 * 원가는 어드민 전용이다 — 고객 화면 · 스토어프론트 코드에서 이 타입과 경로를 쓰지 않는다.
 * 고치지 않고 행을 더한다(append-only). 수정 · 삭제 API 는 없다.
 */

/** `/api/v1/admin/skus/{skuId}/costs` — 현재 원가(GET) · 추가(POST). `/histories` 가 이력(GET) */
export function skuCostsPath(skuId: string): string {
  return `/api/v1/admin/skus/${encodeURIComponent(skuId)}/costs`;
}

export const COST_TYPES = ['GOODS', 'PACKAGING'] as const;
export type CostTypeCode = (typeof COST_TYPES)[number];

/** 폼 · 필터에 쓰는 이름 — BE `SkuCostType` 의 이름과 같다(응답 행은 서버 `costType.name` 을 쓴다) */
export const COST_TYPE_LABELS: Record<CostTypeCode, string> = {
  GOODS: '매입 · 제작 원가',
  PACKAGING: '포장 변동비',
};

export type SkuCostItem = {
  costId: string;
  costType: { code: string; name: string; description: string };
  amount: Money;
  /** 적용 시작(포함) */
  effectiveFrom: string;
  reason: string;
  /** 기록한 어드민의 내부 회원 아이디 — 이름을 찾는 API 가 없어 화면에 원문을 내지 않는다 */
  adminMemberId: string;
  /** 기록 시각 */
  createdAt: string;
};

/** 지금 적용되는 유형별 원가. **한 번도 입력하지 않은 유형은 항목이 없다**(0원으로 채우지 않는다) */
export type SkuCurrentCosts = { skuId: string; costs: SkuCostItem[] };

/** POST 본문. `effectiveFrom` 을 빼면 서버 시각(지금)부터다 — "지금부터 적용" 은 화면 시계로 채우지 않는다 */
export type SkuCostCreateRequest = {
  costType: CostTypeCode;
  amount: Money;
  effectiveFrom?: string;
  reason: string;
};

/** 이력 페이지 크기(BE 기본 20, 상한 100) */
export const HISTORY_PAGE_SIZE = 20;

/** 이력 조건 — 유형 빈 값은 전체, page 는 0 부터 */
export type HistoryQuery = { costType: CostTypeCode | ''; page: number };

export function skuCostHistoriesPath(skuId: string, { costType, page }: HistoryQuery): string {
  const params = new URLSearchParams({ page: String(page), size: String(HISTORY_PAGE_SIZE) });
  if (costType) params.set('costType', costType);
  return `${skuCostsPath(skuId)}/histories?${params.toString()}`;
}
/** 사유 최대 길이 — 앞뒤 공백을 뺀 코드 포인트 수(BE `SkuCostReasonValidator`) */
export const REASON_MAX = 200;
/** 적용 시작은 서버 시각 + 5분까지만 받는다(`SKU_COST_001`) — 화면 시계 오차 허용분 */
export const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;
