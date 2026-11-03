import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import { CATEGORY_REQUIRED, PERIOD_INVERTED, VALID_FROM_REQUIRED } from './form';

// 지식 쓰기 실패 → 화면 처리. 코드 정본은 BE `KnowledgeErrorCode` · `KnowledgeValidationMessage`.
// 서버 원문 대신 업무 문구를 보인다(메인 전시 · 사이즈표와 같은 규칙).

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const CHANGED_NOTICE =
  '다른 관리자가 이 문서를 먼저 바꿨습니다. 최신 내용을 다시 불러왔으니 확인한 뒤 다시 시도해 주세요.';
export const TRANSITION_NOTICE =
  '지금 상태에서는 할 수 없는 작업입니다. 최신 내용을 다시 불러왔으니 상태와 유효기간을 확인해 주세요.';
export const UNKNOWN_OUTCOME_NOTICE =
  '처리 결과를 확인하지 못해 다시 불러왔습니다. 반영됐는지 확인한 뒤 필요하면 다시 시도해 주세요.';
/** 생성은 멱등이 아니고 아직 문서 아이디를 모른다 — 다시 누르면 문서가 둘이 될 수 있어 목록에서 먼저 확인하게 한다 */
export const CREATE_UNKNOWN_OUTCOME =
  '등록 결과를 확인하지 못했습니다. 같은 문서가 두 번 생기지 않게 목록에서 등록됐는지 먼저 확인해 주세요.';
export const ACTION_FAILED = '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/** 검증 코드 → [폼 칸, 문구]. 칸 순서는 화면 순서다 */
const FIELD_MESSAGES: Record<string, [string, string]> = {
  SUPPORT_104: ['title', '제목을 공백이 아닌 200자 이내로 입력해 주세요.'],
  SUPPORT_105: ['body', '본문을 공백이 아닌 8,000자 이내로 입력해 주세요.'],
  SUPPORT_106: ['category', CATEGORY_REQUIRED],
  SUPPORT_107: ['validFrom', VALID_FROM_REQUIRED],
  SUPPORT_108: ['validTo', PERIOD_INVERTED],
};

export type WriteFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 대상이 바뀌었거나(경합 · 상태 전이) 결과를 모름 — 상세를 다시 읽고 안내한다 */
  | { kind: 'reload'; notice: string }
  /** 없는 문서(삭제 · 잘못된 주소) */
  | { kind: 'gone' }
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
 * 고치기 · 새 초안 · 승인 · 게시 · 보관 실패.
 * - 401 은 입력을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - 409 `SUPPORT_004`(버전 경합) · `SUPPORT_003`(상태 전이) · 결과 모름(502 · 504)은 다시 읽는다. 같은 요청을 자동으로 다시 보내지 않는다.
 * - 404 `SUPPORT_001` 은 없는 문서다.
 */
export function writeFailure(cause: unknown): WriteFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.fieldErrors.length > 0) return { kind: 'errors', errors: collect(cause.fieldErrors) };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  switch (cause.code) {
    case 'SUPPORT_001':
      return { kind: 'gone' };
    case 'SUPPORT_003':
      return { kind: 'reload', notice: TRANSITION_NOTICE };
    case 'SUPPORT_004':
      return { kind: 'reload', notice: CHANGED_NOTICE };
    case 'SUPPORT_002':
      return { kind: 'errors', errors: { form: [CHECK_FIELDS] } };
  }
  if (cause.kind === 'notFound') return { kind: 'gone' };
  // 409 인데 코드를 모르면 경합으로 본다 — 다시 읽는 편이 안전하다
  if (cause.kind === 'conflict') return { kind: 'reload', notice: CHANGED_NOTICE };
  return {
    kind: 'errors',
    errors: { form: [cause.kind === 'rateLimited' ? cause.message : SAVE_FAILED] },
  };
}

/** 새 문서 등록 실패 — 결과 모름은 다시 읽을 대상이 없어 안내만 한다 */
export function createFailure(
  cause: unknown,
): { kind: 'access'; denied: AccessDenied } | { kind: 'errors'; errors: FormErrors } {
  if (cause instanceof ApiError && cause.kind === 'unknownOutcome')
    return { kind: 'errors', errors: { form: [CREATE_UNKNOWN_OUTCOME] } };
  const result = writeFailure(cause);
  if (result.kind === 'access' || result.kind === 'errors') return result;
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}
