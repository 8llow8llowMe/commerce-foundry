import type { Currency, Money } from '@cf/api-client';

/**
 * delivery 어드민 배송비 정책 API 계약(#11 국내 · #22 해외).
 * 정본: `ShippingPolicyAdminController` · `InternationalShippingPolicyAdminController` 와 그 DTO.
 * 둘 다 전체 교체 PUT + `version`(낙관적 락)이다 — `backend/docs/api-design-guide.md` §6-1.
 */

export const DOMESTIC_POLICY_PATH = '/api/v1/admin/shipping-policies/domestic';
export const INTERNATIONAL_POLICY_PATH = '/api/v1/admin/shipping-policies/international';
/** 국내 정책 교체 이력(#69). 해외 정책 이력 API 는 없다 */
export const DOMESTIC_POLICY_HISTORY_PATH = `${DOMESTIC_POLICY_PATH}/histories`;

export type RemoteArea = {
  remoteAreaId: string;
  name: string;
  postalCodeFrom: string;
  postalCodeTo: string;
  surcharge: Money;
};

/** GET 이 정책 미설정이면 dataBody 가 null 이다 */
export type DomesticPolicy = {
  shippingPolicyId: string;
  baseFee: Money;
  freeShippingThreshold: Money | null;
  remoteAreas: RemoteArea[];
  version: number;
  updatedAt: string;
};

/** 이력 속 그 시점의 국내 정책 값 — 구간 아이디는 싣지 않는다(교체마다 새로 발급돼 뜻이 없다) */
export type DomesticPolicySnapshot = {
  baseFee: Money;
  freeShippingThreshold: Money | null;
  /** 시작 우편번호 오름차순 */
  remoteAreas: Omit<RemoteArea, 'remoteAreaId'>[];
  version: number;
};

/**
 * 국내 정책 교체 이력 한 건 — `GET .../domestic/histories?page=&size=`(최신순, size 1~20).
 * `adminMemberId` 는 내부 회원 아이디라 화면에 내지 않는다(#305).
 */
export type DomesticPolicyHistoryItem = {
  historyId: string;
  adminMemberId: string;
  /** UTC ISO */
  changedAt: string;
  /** 정책을 처음 만든 교체면 null */
  before: DomesticPolicySnapshot | null;
  after: DomesticPolicySnapshot;
};

export type DomesticPolicyRequest = {
  baseFee: MoneyRequest;
  freeShippingThreshold: MoneyRequest | null;
  remoteAreas: {
    name: string;
    postalCodeFrom: string;
    postalCodeTo: string;
    surcharge: MoneyRequest;
  }[];
  /** 처음 만들 때만 null */
  version: number | null;
};

export type ShippingZoneRate = {
  shippingZoneRateId: string;
  maxWeightGrams: number;
  fee: Money;
};

export type ShippingZone = {
  shippingZoneId: string;
  name: string;
  countryCodes: string[];
  rates: ShippingZoneRate[];
};

export type InternationalPolicy = {
  shippingPolicyId: string;
  baseFee: Money;
  freeShippingThreshold: Money | null;
  customsNotice: string | null;
  zones: ShippingZone[];
  version: number;
  updatedAt: string;
};

/** `remoteAreas` 는 해외에 없다 — 보내면 400 DELIVERY_136 */
export type InternationalPolicyRequest = {
  baseFee: MoneyRequest;
  freeShippingThreshold: MoneyRequest | null;
  customsNotice: string | null;
  zones: {
    name: string;
    countryCodes: string[];
    rates: { maxWeightGrams: number | null; fee: MoneyRequest }[];
  }[];
  version: number | null;
};

/** 빈 칸은 amount null 로 보낸다 — 필수 여부는 서버 검증(DELIVERY_102)이 정본이다 */
export type MoneyRequest = { amount: number | null; currency: Currency };
