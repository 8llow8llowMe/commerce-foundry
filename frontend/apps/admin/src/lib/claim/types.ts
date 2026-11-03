import type { Money } from '@cf/api-client';
import type { CodeName } from '@/lib/product/detail';

/**
 * 어드민 클레임 조회 API(#17 · BE PR #574, order-service `ClaimAdminController`). 읽기 전용이다 —
 * 승인 · 거절 · 환불 실행 · 환불 실패 재시도는 아직 API 가 없다(BE #17 잔여) — 화면에도 자리를 두지 않는다.
 * BFF 는 범용 프록시(`app/api/[...path]`)가 그대로 넘긴다.
 */
export const ADMIN_CLAIMS_PATH = '/api/v1/admin/claims';
export const adminClaimPath = (claimId: string) =>
  `${ADMIN_CLAIMS_PATH}/${encodeURIComponent(claimId)}`;

/** 서버 `ClaimAdminFilter.Type` — 목록 조건에만 쓴다. 지금은 반품만 받는다(다른 값은 400) */
export const CLAIM_TYPES = ['RETURN'] as const;
export type ClaimTypeCode = (typeof CLAIM_TYPES)[number];

/** 서버 `ClaimAdminFilter.Status` — 목록 조건에만 쓴다. 지금은 접수만 받는다(다른 값은 400) */
export const CLAIM_STATUSES = ['REQUESTED'] as const;
export type ClaimStatusCode = (typeof CLAIM_STATUSES)[number];

/** 조건 고르기용 이름 — 서버 `Claim.Type` · `Claim.Status` displayName 과 같다. 응답에 온 값은 응답의 `name` 을 그대로 보인다 */
export const CLAIM_TYPE_LABELS: Record<ClaimTypeCode, string> = { RETURN: '반품' };
export const CLAIM_STATUS_LABELS: Record<ClaimStatusCode, string> = { REQUESTED: '접수' };

/** `ClaimAdminItem`. 시각은 UTC ISO 다 */
export type ClaimListItem = {
  claimId: string;
  orderId: string;
  type: CodeName;
  status: CodeName;
  /** 예상 환불액 — null 은 미산정이며 0원과 다르다. 화면은 계산하지 않고 그대로 보인다 */
  expectedRefund: Money | null;
  requestedAt: string;
};

/**
 * `ClaimAdminLineItem`. 상품 이름 · 옵션은 내려오지 않는다 — 주문 상세 응답에도 주문 항목 아이디가 없어
 * 화면에서 이어 붙이지 않는다(순서로 짐작하지 않는다). 상품은 주문 상세에서 확인한다.
 */
export type ClaimLine = {
  orderItemId: string;
  quantity: number;
  reason: CodeName;
};

/** `ClaimAdminResponse` — 항목은 주문 항목 아이디 오름차순 */
export type ClaimDetail = ClaimListItem & { items: ClaimLine[] };
