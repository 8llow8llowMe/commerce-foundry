import type { Currency, Money } from '@cf/api-client';

/**
 * delivery 어드민 배송비 정책 API 계약(#11 국내 · #22 해외).
 * 정본: `ShippingPolicyAdminController` · `InternationalShippingPolicyAdminController` 와 그 DTO.
 * 둘 다 전체 교체 PUT + `version`(낙관적 락)이다 — `backend/docs/api-design-guide.md` §6-1.
 */

export const DOMESTIC_POLICY_PATH = '/api/v1/admin/shipping-policies/domestic';
export const INTERNATIONAL_POLICY_PATH = '/api/v1/admin/shipping-policies/international';

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
