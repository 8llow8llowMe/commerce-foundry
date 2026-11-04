import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import { BODY_INVALID, PLACEHOLDER_LEFT, TITLE_INVALID } from './form';

// 약관 쓰기(초안 저장 · 게시) 실패 → 화면 처리. 코드 정본은 `backend/docs/store-legal-guide.md` "오류".
// 서버 원문 대신 업무 문구를 보인다(지식 관리 · 메인 전시와 같은 규칙).

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const CONFLICT_NOTICE =
  '다른 관리자가 이 문서를 먼저 바꿨습니다. 입력은 그대로 두고 최신 내용을 다시 불러왔으니 아래에서 비교한 뒤 다시 저장해 주세요.';
export const UNKNOWN_OUTCOME_NOTICE =
  '처리 결과를 확인하지 못해 최신 내용을 다시 불러왔습니다. 이력에서 반영됐는지 확인한 뒤 필요하면 다시 시도해 주세요.';
export const REVIEW_REQUIRED = '게시 전 검토 확인에 체크해 주세요.';

/** 검증 코드 → [폼 칸, 문구] */
const FIELD_MESSAGES: Record<string, [string, string]> = {
  LEGAL_DOCUMENT_102: ['title', TITLE_INVALID],
  LEGAL_DOCUMENT_103: ['body', BODY_INVALID],
  LEGAL_DOCUMENT_105: ['review', REVIEW_REQUIRED],
};

export type WriteFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 경합(409) · 결과 모름 — 입력은 지키고 최신 상세를 다시 읽어 비교하게 한다 */
  | { kind: 'reload'; notice: string; conflict: boolean }
  | { kind: 'errors'; errors: FormErrors };

function collect(fieldErrors: ApiError['fieldErrors']): FormErrors {
  const errors: FormErrors = {};
  for (const item of fieldErrors) {
    const [key, message] = FIELD_MESSAGES[item.code] ?? ['form', CHECK_FIELDS];
    if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
  }
  if (!errors.form) errors.form = [CHECK_FIELDS];
  return errors;
}

/**
 * 초안 저장 · 게시 실패.
 * - 401 은 입력을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - 409 `LEGAL_DOCUMENT_020`(버전 불일치 · 최신이 아닌 게시 · 동시 갱신)과 결과 모름(502 · 504)은 다시 읽는다.
 *   초안 저장은 멱등이 아니라(같은 본문도 새 리비전) **자동으로 다시 보내지 않는다.**
 * - 400 `021`(검토 확인 누락) · `022`(빈칸 남음)는 게시 확인 안의 오류로 보인다.
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
    case 'LEGAL_DOCUMENT_020':
      return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
    case 'LEGAL_DOCUMENT_021':
    case 'LEGAL_DOCUMENT_105':
      return { kind: 'errors', errors: { review: [REVIEW_REQUIRED] } };
    case 'LEGAL_DOCUMENT_022':
      return { kind: 'errors', errors: { form: [PLACEHOLDER_LEFT] } };
    case 'LEGAL_DOCUMENT_102':
      return { kind: 'errors', errors: { title: [TITLE_INVALID], form: [CHECK_FIELDS] } };
    case 'LEGAL_DOCUMENT_103':
      return { kind: 'errors', errors: { body: [BODY_INVALID], form: [CHECK_FIELDS] } };
    // 버전 · 리비전 번호 오류는 화면이 들고 있는 기준이 틀어졌다는 뜻이다 — 다시 읽는다
    case 'LEGAL_DOCUMENT_101':
    case 'LEGAL_DOCUMENT_104':
      return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
  }
  // 409 인데 코드를 모르면 경합으로 본다 — 다시 읽는 편이 안전하다
  if (cause.kind === 'conflict') return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
  return {
    kind: 'errors',
    errors: { form: [cause.kind === 'rateLimited' ? cause.message : SAVE_FAILED] },
  };
}
