import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  COPYRIGHT_INVALID,
  LABEL_INVALID,
  TOO_MANY_LINKS,
  URL_CUSTOM_HOST,
  URL_FORMAT,
} from './form';
import { VERSION_CONFLICT } from './types';

// 푸터 쓰기(초안 저장 · 게시) 실패 → 화면 처리. 코드 정본은 `backend/docs/store-footer-guide.md` "오류".
// 서버 원문 대신 업무 문구를 보인다(약관 · 메인 전시와 같은 규칙).

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const CONFLICT_NOTICE =
  '다른 관리자가 푸터 설정을 먼저 바꿨습니다. 입력은 그대로 두고 최신 상태를 다시 불러왔습니다. 최신 값으로 바꾸거나, 확인한 뒤 내 입력으로 다시 저장해 주세요.';
export const UNKNOWN_OUTCOME_NOTICE =
  '처리 결과를 확인하지 못해 최신 상태를 다시 불러왔습니다. 공개 상태 문구로 반영됐는지 확인한 뒤 필요하면 다시 시도해 주세요.';

/** 서버 fieldErrors 의 코드 → 문구. 칸은 `field` 경로로 정한다 */
const CODE_MESSAGES: Record<string, string> = {
  STORE_FOOTER_102: COPYRIGHT_INVALID,
  STORE_FOOTER_103: TOO_MANY_LINKS,
  STORE_FOOTER_105: LABEL_INVALID,
  STORE_FOOTER_106: URL_FORMAT,
};

export type WriteFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 경합(409) · 결과 모름 — 입력은 지키고 최신 상세를 다시 읽어 비교하게 한다 */
  | { kind: 'reload'; notice: string; conflict: boolean }
  | { kind: 'errors'; errors: FormErrors };

function collect(fieldErrors: ApiError['fieldErrors']): FormErrors {
  const errors: FormErrors = {};
  for (const item of fieldErrors) {
    const key = item.field ? fieldKey(item.field) : 'form';
    const message = CODE_MESSAGES[item.code] ?? item.message ?? CHECK_FIELDS;
    if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
  }
  if (!errors.form) errors.form = [CHECK_FIELDS];
  return errors;
}

/**
 * 초안 저장 · 게시 실패.
 * - 401 은 입력을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - 409 `STORE_FOOTER_020` 과 결과 모름(502 · 504)은 다시 읽는다. 초안 저장은 멱등이 아니라(같은 본문도 새 리비전)
 *   **자동으로 다시 보내지 않는다.**
 * - 도메인 규칙의 URL 거절(`106`, 칸 정보 없음)은 화면 검사를 통과한 뒤라면 기타 링크의 허용 도메인 문제다.
 */
export function writeFailure(cause: unknown): WriteFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.fieldErrors.length > 0) return { kind: 'errors', errors: collect(cause.fieldErrors) };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE, conflict: false };
  switch (cause.code) {
    case VERSION_CONFLICT:
    // 버전 · 리비전 번호 오류는 화면이 들고 있는 기준이 틀어졌다는 뜻이다 — 다시 읽는다
    case 'STORE_FOOTER_101':
    case 'STORE_FOOTER_109':
      return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
    case 'STORE_FOOTER_106':
      return { kind: 'errors', errors: { form: [URL_CUSTOM_HOST] } };
    case 'STORE_FOOTER_102':
    case 'STORE_FOOTER_103':
    case 'STORE_FOOTER_105':
      return { kind: 'errors', errors: { form: [CODE_MESSAGES[cause.code] ?? CHECK_FIELDS] } };
  }
  if (cause.kind === 'conflict') return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
  return {
    kind: 'errors',
    errors: {
      form: [
        cause.kind === 'rateLimited'
          ? cause.message
          : cause.status === 400
            ? CHECK_FIELDS
            : SAVE_FAILED,
      ],
    },
  };
}
