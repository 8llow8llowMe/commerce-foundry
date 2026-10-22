// auth-service 내 정보 · 배송지 주소록 API 계약 (`/api/v1/members/me`, `/api/v1/members/me/shipping-addresses`, BE #7).
// 정본은 BE `backend/docs/api-design-guide.md` §2-4 다.

export type MemberProfile = {
  memberId: string;
  email: string;
  name: string;
  /** 숫자만 저장된 국내 휴대폰. 없으면 null */
  phone: string | null;
  marketingAgreed: boolean;
};

/** `PATCH /api/v1/members/me` — 보낸 칸만 바꾼다. phone 은 빈 문자열이 "지우기" 다 */
export type ProfilePatch = Partial<{ name: string; phone: string; marketingAgreed: boolean }>;

/** 회원 배송지 경로 — 배송지 관리와 주문서가 같이 읽는다 */
export const ADDRESSES_PATH = '/api/v1/members/me/shipping-addresses';

export type ShippingAddress = {
  addressId: string;
  label: string | null;
  recipientName: string;
  /** 하이픈 · 공백을 뺀 값. 해외는 `+` 로 시작할 수 있다 */
  recipientPhone: string;
  /** ISO 3166-1 alpha-2 대문자 */
  countryCode: string;
  postalCode: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string | null;
  stateProvince: string | null;
  isDefault: boolean;
};

/** `GET …/shipping-addresses` — 기본 먼저, 그다음 최신순 */
export type ShippingAddressList = { addresses: ShippingAddress[]; maxCount: number };

/**
 * 추가(`POST`) · 전체 교체(`PUT`) 본문. PUT 은 보내지 않은 선택 칸을 지우므로 언제나 모든 칸을 보낸다.
 * `isDefault` 는 추가에서만 의미가 있다(PUT 은 무시).
 */
export type ShippingAddressBody = {
  label: string | null;
  recipientName: string;
  recipientPhone: string;
  countryCode: string;
  postalCode: string | null;
  addressLine1: string;
  addressLine2: string | null;
  city: string | null;
  stateProvince: string | null;
  isDefault?: boolean;
};
