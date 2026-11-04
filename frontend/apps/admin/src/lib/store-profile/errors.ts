import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import { FIELDS, VERSION_CONFLICT } from './types';

export const CONFLICT_NOTICE =
  '다른 곳에서 쇼핑몰 정보가 먼저 저장됐습니다. 입력한 값은 그대로 두었습니다. 아래 최신 값과 비교한 뒤 다시 저장하거나 최신 값으로 바꿔 주세요.';
/** 502 · 504 — 저장됐을 수도 있다. 실패로 단정하지 않고 최신 값과 비교하게 한다(자동 재시도 없음) */
export const UNKNOWN_OUTCOME_NOTICE =
  '저장 결과를 확인하지 못했습니다. 입력한 값은 그대로 두었습니다. 아래 최신 값과 비교해 저장됐는지 확인해 주세요.';
/** 결과를 모르던 저장이 실제로 반영됐다(최신 값과 내 입력이 같다) */
export const SAVED_AFTER_UNKNOWN = '저장된 값을 확인했습니다. 공개 정보에 반영됐습니다.';
export const RELOAD_FAILED =
  '최신 값을 불러오지 못했습니다. 입력한 값은 그대로 두었습니다. 잠시 후 다시 저장해 주세요.';
export const CHECK_FIELDS = '입력값을 확인해 주세요.';

const FIELD_KEYS = new Set<string>(FIELDS.map((field) => field.key));

export type SaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'conflict' }
  | { kind: 'unknownOutcome' }
  | { kind: 'errors'; errors: FormErrors };

/** 저장(PUT) 실패를 화면 처리로 가른다. 401 은 폼을 지키고 다시 로그인을 안내한다 */
export function storeProfileSaveFailure(cause: unknown): SaveFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === VERSION_CONFLICT) return { kind: 'conflict' };
  if (cause.kind === 'unknownOutcome') return { kind: 'unknownOutcome' };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const field = fieldKey(item.field);
      const key = FIELD_KEYS.has(field) ? field : 'form';
      errors[key] = [...(errors[key] ?? []), item.message];
    }
    if (!errors.form) errors.form = [CHECK_FIELDS];
    return { kind: 'errors', errors };
  }
  // STORE_PROFILE_100(알 수 없는 국가 코드 · 깨진 요청 등)은 서버 문구를 폼 전체 오류로
  if (cause.kind === 'validation' || cause.status === 400)
    return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}
