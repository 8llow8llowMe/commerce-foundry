import { ApiError } from '@cf/api-client';
import type { Dictionary } from '@/lib/i18n/dictionaries';
import { accountErrorText } from './address-form';
import type { MemberProfile, ProfilePatch } from './types';

export type ProfileDraft = { name: string; phone: string; marketingAgreed: boolean };
export type ProfileField = keyof ProfileDraft;
export type ProfileErrors = Record<ProfileField | 'form', string[]>;

export const NO_PROFILE_ERRORS: ProfileErrors = {
  form: [],
  name: [],
  phone: [],
  marketingAgreed: [],
};

export function profileDraft(profile: MemberProfile): ProfileDraft {
  return {
    name: profile.name,
    phone: profile.phone ?? '',
    marketingAgreed: profile.marketingAgreed,
  };
}

/**
 * 바뀐 칸만 담은 PATCH 본문. 바뀐 것이 없으면 null — 보내면 400 `AUTH_115` 다.
 * 이름은 앞뒤 공백을 지워 비교한다(BE 가 지우고 저장한다). 연락처는 하이픈 · 공백을 빼고 비교하고(저장값은 숫자만),
 * 비우면 빈 문자열을 보낸다 — BE 에서 `""` 만 "지우기" 다.
 */
export function profilePatch(original: MemberProfile, draft: ProfileDraft): ProfilePatch | null {
  const patch: ProfilePatch = {};
  const name = draft.name.trim();
  if (name !== original.name) patch.name = name;

  const digits = draft.phone.replace(/[-\s]/g, '');
  // 숫자만 보낸다 — BE 는 하이픈은 받지만 공백은 받지 않고, 저장은 숫자만이다
  if (digits !== (original.phone ?? '')) patch.phone = digits;

  if (draft.marketingAgreed !== original.marketingAgreed) {
    patch.marketingAgreed = draft.marketingAgreed;
  }
  return Object.keys(patch).length > 0 ? patch : null;
}

const PROFILE_FIELDS = new Set<string>(['name', 'phone', 'marketingAgreed']);

/** 내 정보 수정 실패를 칸 · 폼 문구로. 동시 수정(409 `AUTH_004`)은 폼 문구이고 다시 읽는 일은 호출자가 한다 */
export function profileErrors(cause: unknown, t: Dictionary['account']): ProfileErrors {
  if (cause instanceof ApiError && cause.fieldErrors.length > 0) {
    const textOf = (code: string | null, fallback: string) => (code && t.errors[code]) || fallback;
    const errors: ProfileErrors = { ...NO_PROFILE_ERRORS };
    let other = false;
    for (const item of cause.fieldErrors) {
      if (!PROFILE_FIELDS.has(item.field)) {
        other = true;
        continue;
      }
      const field = item.field as ProfileField;
      errors[field] = [...errors[field], textOf(item.code, item.message)];
    }
    return other ? { ...errors, form: [t.profile.failed] } : errors;
  }
  return { ...NO_PROFILE_ERRORS, form: [accountErrorText(cause, t, t.profile.failed)] };
}
