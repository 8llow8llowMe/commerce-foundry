import { ApiError } from '@cf/api-client';
import { accessDeniedOf, fieldKey, SAVE_FAILED, type AccessDenied } from '@/lib/api-errors';
import type { FormErrors } from './policy-form';

/** 다른 곳에서 먼저 저장했다(조회한 version 과 다르다) — 최신 값을 다시 읽는다 */
export const VERSION_CONFLICT = 'DELIVERY_006';
export const CONFLICT_NOTICE =
  '다른 곳에서 정책이 먼저 바뀌어 최신 값을 다시 불러왔습니다. 확인한 뒤 다시 저장해 주세요.';
/** 502 · 504 — 저장됐을 수도 있다. 실패로 단정하지 않고 다시 읽어 확인하게 한다(자동 재시도 없음) */
export const UNKNOWN_OUTCOME_NOTICE =
  '저장 결과를 확인하지 못해 최신 값을 다시 불러왔습니다. 저장됐는지 확인해 주세요.';
/** 칸 오류만 있을 때 저장 버튼 곁에 두는 요약 — 오류 칸이 화면 밖일 수 있다 */
export const CHECK_FIELDS = '입력값을 확인해 주세요.';

/** 폼이 그리는 칸 키. 이 밖의 키(`request` · 클래스 수준 오류 등)는 폼 전체 오류로 모은다 */
const FORM_FIELD =
  /^(baseFee|freeShippingThreshold|currency|customsNotice|remoteAreas|zones)(\.|$)/;

/**
 * 도메인 오류를 붙일 칸. 서버가 어느 줄인지 알려 주지 않는 오류는 그 묶음(구간 · 그룹) 칸에 붙인다.
 * 문구는 서버 문구를 쓴다(어드민은 국문만).
 */
const DOMAIN_TARGETS: Record<string, string> = {
  DELIVERY_003: 'form', // 금액 통화 불일치
  DELIVERY_004: 'remoteAreas', // 도서산간 시작 > 끝
  DELIVERY_005: 'remoteAreas', // 도서산간 구간 겹침
  DELIVERY_007: 'zones', // 국가 중복
  DELIVERY_008: 'zones', // 같은 그룹 무게 상한 중복
  DELIVERY_009: 'zones', // 국내(KR) 포함
  DELIVERY_010: 'zones', // 그룹 이름 중복
  DELIVERY_012: 'currency', // 저장된 정책의 통화를 바꿀 수 없다
};

export type SaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'conflict' }
  | { kind: 'unknownOutcome' }
  | { kind: 'errors'; errors: FormErrors };

/** 정책 저장(PUT) 실패를 화면 처리로 가른다 */
export function policySaveFailure(cause: unknown): SaveFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === VERSION_CONFLICT) return { kind: 'conflict' };
  if (cause.kind === 'unknownOutcome') return { kind: 'unknownOutcome' };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const field = fieldKey(item.field);
      const key = FORM_FIELD.test(field) ? field : 'form';
      errors[key] = [...(errors[key] ?? []), item.message];
    }
    return { kind: 'errors', errors: withSummary(errors) };
  }
  const target = cause.code ? DOMAIN_TARGETS[cause.code] : undefined;
  if (target) return { kind: 'errors', errors: withSummary({ [target]: [cause.message] }) };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

/** 칸 오류만 있으면 폼 전체 오류 자리에 요약을 더한다(폼 전체 오류가 이미 있으면 그대로) */
export function withSummary(errors: FormErrors): FormErrors {
  if (errors.form || Object.keys(errors).length === 0) return errors;
  return { ...errors, form: [CHECK_FIELDS] };
}
