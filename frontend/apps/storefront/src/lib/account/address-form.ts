import { ApiError } from '@cf/api-client';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import type { ShippingAddress, ShippingAddressBody } from './types';

export const DOMESTIC_COUNTRY = 'KR';

export type AddressField =
  | 'label'
  | 'recipientName'
  | 'recipientPhone'
  | 'countryCode'
  | 'postalCode'
  | 'addressLine1'
  | 'addressLine2'
  | 'city'
  | 'stateProvince';

export type AddressErrors = Record<AddressField | 'form', string[]>;

export const NO_ADDRESS_ERRORS: AddressErrors = {
  form: [],
  label: [],
  recipientName: [],
  recipientPhone: [],
  countryCode: [],
  postalCode: [],
  addressLine1: [],
  addressLine2: [],
  city: [],
  stateProvince: [],
};

/** 폼 입력값. 모두 문자열로 두고 보낼 때 빈 선택 칸을 null 로 바꾼다 */
export type AddressDraft = Record<AddressField, string> & { isDefault: boolean };

export function emptyDraft(countryCode: string): AddressDraft {
  return {
    label: '',
    recipientName: '',
    recipientPhone: '',
    countryCode,
    postalCode: '',
    addressLine1: '',
    addressLine2: '',
    city: '',
    stateProvince: '',
    isDefault: false,
  };
}

export function draftFrom(address: ShippingAddress): AddressDraft {
  return {
    label: address.label ?? '',
    recipientName: address.recipientName,
    recipientPhone: address.recipientPhone,
    countryCode: address.countryCode,
    postalCode: address.postalCode ?? '',
    addressLine1: address.addressLine1,
    addressLine2: address.addressLine2 ?? '',
    city: address.city ?? '',
    stateProvince: address.stateProvince ?? '',
    isDefault: address.isDefault,
  };
}

/**
 * 폼 값을 요청 본문으로. 앞뒤 공백을 지우고 빈 선택 칸은 null 이다 — 전체 교체(PUT)는 보내지 않은 칸을 지우므로 언제나 모든 칸을 싣는다.
 * 형식(연락처 · 우편번호 · 국가별 필수 칸)은 BE 가 정본이라 여기서 거르지 않는다. `withDefault` 는 추가(POST)만 — PUT 은 기본 여부를 무시한다.
 */
export function toAddressBody(draft: AddressDraft, withDefault: boolean): ShippingAddressBody {
  const optional = (value: string) => value.trim() || null;
  const countryCode = draft.countryCode.trim().toUpperCase();
  // 국내는 도시 · 주 칸을 보이지 않는다 — 해외에서 국내로 바꾼 뒤 숨은 값이 저장되지 않게 비운다
  const domestic = countryCode === DOMESTIC_COUNTRY;
  const body: ShippingAddressBody = {
    label: optional(draft.label),
    recipientName: draft.recipientName.trim(),
    recipientPhone: draft.recipientPhone.trim(),
    countryCode,
    postalCode: optional(draft.postalCode),
    addressLine1: draft.addressLine1.trim(),
    addressLine2: optional(draft.addressLine2),
    city: domestic ? null : optional(draft.city),
    stateProvince: domestic ? null : optional(draft.stateProvince),
  };
  if (withDefault) body.isDefault = draft.isDefault;
  return body;
}

/** 국내는 우편번호, 해외는 도시가 필수다(BE 125 · 129). 화면의 required 표시에만 쓴다 */
export function requiredFields(countryCode: string): ReadonlySet<AddressField> {
  const common: AddressField[] = ['recipientName', 'recipientPhone', 'countryCode', 'addressLine1'];
  return new Set(
    countryCode === DOMESTIC_COUNTRY ? [...common, 'postalCode'] : [...common, 'city'],
  );
}

const ADDRESS_FIELDS = new Set<string>(Object.keys(NO_ADDRESS_ERRORS).filter((k) => k !== 'form'));

/**
 * 배송지 저장 실패를 칸 · 폼 문구로. 서버 코드로 사전 문구를 고르고(영문 스토어에서도 같은 뜻), 없으면 서버 문구다.
 * 상한(021) · 동시 변경(022) · 회원 잠금 대기(023) · 없어진 주소(020)는 폼 문구다 — 목록을 다시 읽는 일은 호출자가 한다.
 */
export function addressErrors(cause: unknown, t: Dictionary['account']): AddressErrors {
  if (!(cause instanceof ApiError)) return { ...NO_ADDRESS_ERRORS, form: [t.address.failed] };
  const textOf = (code: string | null, fallback: string) => (code && t.errors[code]) || fallback;

  if (cause.fieldErrors.length > 0) {
    const errors: AddressErrors = { ...NO_ADDRESS_ERRORS };
    let other = false;
    for (const item of cause.fieldErrors) {
      if (!ADDRESS_FIELDS.has(item.field)) {
        other = true;
        continue;
      }
      const field = item.field as AddressField;
      errors[field] = [...errors[field], textOf(item.code, item.message)];
    }
    return other ? { ...errors, form: [t.address.failed] } : errors;
  }
  return { ...NO_ADDRESS_ERRORS, form: [accountErrorText(cause, t, t.address.failed)] };
}

/** 칸이 없는 계정 API 실패 문구 — 서버 코드가 사전에 있으면 그것, 아니면 `fallback` */
export function accountErrorText(
  cause: unknown,
  t: Dictionary['account'],
  fallback: string,
): string {
  if (cause instanceof ApiError) {
    if (cause.kind === 'rateLimited') return t.errors.GATEWAY_006 ?? fallback;
    if (cause.code && t.errors[cause.code]) return t.errors[cause.code]!;
  }
  return fallback;
}

/** 다시 읽어야 하는 실패 — 다른 요청이 먼저 바꿨거나(022) 이미 없는 주소(020)다 */
export function isStaleAddress(cause: unknown): boolean {
  return cause instanceof ApiError && (cause.code === 'AUTH_020' || cause.code === 'AUTH_022');
}

/**
 * 쓰기 결과를 모르는 실패 — 게이트웨이가 하류 응답을 못 받았거나(502 · 504) 연결이 끊겼다. 배송지 쓰기에는 멱등 키가 없어
 * 다시 보내면 같은 주소가 하나 더 생길 수 있으므로, 다시 저장하게 두지 않고 목록을 다시 읽는다(`api-client.md` §3).
 */
export function isUnknownOutcome(cause: unknown): boolean {
  return cause instanceof ApiError && (cause.kind === 'unknownOutcome' || cause.kind === 'network');
}

/**
 * 저장된 연락처(숫자만, 해외는 `+` 포함)를 읽기 좋게. 국내만 하이픈을 넣고 그 밖은 그대로 둔다.
 * 02 지역번호는 2자리, 나머지는 3자리 국번이다.
 */
export function formatPhone(phone: string, countryCode: string): string {
  if (countryCode !== DOMESTIC_COUNTRY || !/^0\d{8,10}$/.test(phone)) return phone;
  const head = phone.startsWith('02') ? 2 : 3;
  const tail = phone.slice(head);
  const mid = tail.length - 4;
  return `${phone.slice(0, head)}-${tail.slice(0, mid)}-${tail.slice(mid)}`;
}
