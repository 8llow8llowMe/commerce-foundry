import type { FormErrors } from '@/lib/api-errors';
import {
  FIELDS,
  type StoreProfile,
  type StoreProfileField,
  type StoreProfileFields,
  type StoreProfileRequest,
} from './types';

// 쇼핑몰 공개 정보 폼 ↔ API 값. 입력 규칙은 BE `StoreProfileSaveRequest` 와 같다(정본은 서버다).
// 사업자 식별 번호 형식은 국가마다 달라 서버도 화면도 검사하지 않는다.

/** 칸은 글자 그대로 둔다. version 은 화면에 보이지 않고 저장 때 expectedVersion 으로만 보낸다 */
export type StoreProfileForm = Record<StoreProfileField, string> & {
  /** null 이면 아직 등록 전(처음 만들기) */
  version: number | null;
};

export const REQUIRED = (label: string) => `${label}을(를) 입력해 주세요.`;
export const TOO_LONG = (label: string, max: number) =>
  `${label}은(는) ${max}자 이하로 입력해 주세요.`;
export const COUNTRY_FORMAT = '국가 코드는 ISO 영문 대문자 2자리로 입력해 주세요(예: KR, US, JP).';
export const EMAIL_FORMAT = '이메일 형식이 올바르지 않습니다.';
export const CONTROL_CHAR = '줄바꿈 · 탭 같은 제어 문자는 넣을 수 없습니다.';

// BE 와 같이 Unicode Cc(제어 문자)를 막는다
const CONTROL = /\p{Cc}/u;
const EMAIL = /^[^\s@]+@[^\s@]+$/;

/** 서버 값(없으면 null = 미등록)으로 채운 폼 */
export function storeProfileForm(profile: StoreProfile | null): StoreProfileForm {
  const form = { version: profile?.version ?? null } as StoreProfileForm;
  for (const { key } of FIELDS) form[key] = profile?.[key] ?? '';
  return form;
}

/** 폼 칸을 보낼 값으로 — 앞뒤 공백을 빼고, 빈 선택 칸은 null(지우기) */
export function fieldsOf(form: StoreProfileForm): StoreProfileFields {
  const fields = {} as Record<StoreProfileField, string | null>;
  for (const { key, required } of FIELDS) {
    const value = form[key].trim();
    fields[key] = value === '' && !required ? null : value;
  }
  return fields as StoreProfileFields;
}

/** 폼 → PUT 본문. 어기면 칸 오류를 돌려주고 보내지 않는다. 길이는 Java String 길이(UTF-16 단위)로 센다 */
export function storeProfileRequest(
  form: StoreProfileForm,
): { ok: true; request: StoreProfileRequest } | { ok: false; errors: FormErrors } {
  const fields = fieldsOf(form);
  const errors: FormErrors = {};
  for (const { key, label, max, required } of FIELDS) {
    const value = fields[key];
    if (value === null || value === '') {
      if (required) errors[key] = [REQUIRED(label)];
    } else if (CONTROL.test(value)) errors[key] = [CONTROL_CHAR];
    else if (value.length > max) errors[key] = [TOO_LONG(label, max)];
  }
  if (!errors.countryCode && !/^[A-Z]{2}$/.test(fields.countryCode))
    errors.countryCode = [COUNTRY_FORMAT];
  if (!errors.customerServiceEmail && !EMAIL.test(fields.customerServiceEmail))
    errors.customerServiceEmail = [EMAIL_FORMAT];

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return { ok: true, request: { ...fields, expectedVersion: form.version } };
}

/** 충돌 비교 한 줄 — 내가 고친 값과 다른 곳에서 먼저 저장한 최신 값 */
export type FieldDiff = { key: StoreProfileField; label: string; mine: string; latest: string };

/** 내 입력과 최신 서버 값이 다른 칸(보낼 값 기준으로 비교한다 — 앞뒤 공백 차이는 같은 값) */
export function diffFields(form: StoreProfileForm, latest: StoreProfile | null): FieldDiff[] {
  const mine = fieldsOf(form);
  return FIELDS.flatMap(({ key, label }) => {
    const theirs = latest?.[key] ?? null;
    return (mine[key] ?? null) === theirs
      ? []
      : [{ key, label, mine: mine[key] ?? '', latest: theirs ?? '' }];
  });
}
