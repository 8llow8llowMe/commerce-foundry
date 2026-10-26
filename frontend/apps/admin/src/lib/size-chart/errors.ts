import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  cellKey,
  COLUMN_NAME_DUPLICATED,
  COLUMN_NAME_REQUIRED,
  labelKey,
  REJECTED_CHARACTER,
  SIZE_LABEL_DUPLICATED,
  SIZE_LABEL_REQUIRED,
  TEMPLATE_NAME_REQUIRED,
  TEMPLATE_REQUIRED,
  tooLong,
  VALUE_RULE,
} from './form';
import { LIMITS, type TemplateColumn } from './types';

// 사이즈표 쓰기 실패 → 화면 처리. 서버 검증 문구에는 칸 이름이 영문으로 들어 있어 그대로 보이지 않고
// 코드별 업무 문구를 쓴다(메인 전시 #305 와 같은 규칙). 코드 정본은 BE `SizeChartErrorCode` · `SizeChartValidationMessage`.

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const BUSY = '다른 관리자가 같은 사이즈표를 저장하는 중입니다. 잠시 후 다시 시도해 주세요.';
export const UNKNOWN_OUTCOME_NOTICE =
  '저장 결과를 확인하지 못해 다시 불러왔습니다. 반영됐는지 확인해 주세요.';
export const TEMPLATE_GONE_NOTICE = '다른 곳에서 삭제된 템플릿입니다. 목록을 다시 불러왔습니다.';
export const TEMPLATE_IN_USE_STRUCTURE =
  '이 템플릿을 쓰는 상품 사이즈표가 있어 단위를 바꾸거나 항목을 더하고 뺄 수 없습니다. 이름 · 설명 · 순서만 바꿀 수 있습니다. 구조가 다른 표가 필요하면 새 템플릿을 만들어 주세요.';
export const TEMPLATE_IN_USE_DELETE =
  '이 템플릿을 쓰는 상품 사이즈표가 있어 삭제할 수 없습니다. 그 상품들을 다른 템플릿으로 바꾼 뒤 삭제해 주세요.';
export const TEMPLATE_CHANGED =
  '템플릿 항목이 그사이 바뀌었습니다. 템플릿을 다시 불러왔으니 치수를 확인한 뒤 저장해 주세요.';
export const TEMPLATE_CHANGED_DROPPED =
  '템플릿 항목이 그사이 바뀌었습니다. 템플릿을 다시 불러왔고, 없어진 항목의 치수는 지워졌습니다. 표를 확인한 뒤 저장해 주세요.';
/** 편집 중 템플릿 읽기가 401 — 입력을 지키려고 로그인 화면으로 보내지 않는다(쓰기 401 과 같은 이유) */
export const SESSION_EXPIRED_ON_READ =
  '로그인이 만료되어 템플릿을 불러오지 못했습니다. 입력한 값은 그대로 두었습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 골라 주세요.';
export const PRODUCT_GONE = '상품을 찾을 수 없습니다. 삭제된 상품일 수 있습니다.';
export const CHART_CONFLICT_NOTICE =
  '다른 곳에서 이 상품의 사이즈표를 먼저 저장했습니다. 최신 값을 다시 불러왔으니 확인한 뒤 다시 저장해 주세요.';
export const PICKED_TEMPLATE_GONE = '고른 템플릿이 삭제되었습니다. 다른 템플릿을 골라 주세요.';
export const DELETE_FAILED = '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/** 칸 오류만 있으면 저장 버튼 위에 요약을 둔다 — 오류 칸이 화면 밖일 수 있다 */
export function withSummary(errors: FormErrors): FormErrors {
  if (errors.form || Object.keys(errors).length === 0) return errors;
  return { ...errors, form: [CHECK_FIELDS] };
}

/** 검증 코드 → 문구. 칸은 경로로 찾는다 */
const MESSAGES: Record<string, string> = {
  PRODUCT_170: TEMPLATE_NAME_REQUIRED,
  PRODUCT_171: tooLong(LIMITS.templateName),
  PRODUCT_172: REJECTED_CHARACTER,
  PRODUCT_174: `항목은 ${LIMITS.minColumns}~${LIMITS.maxColumns}개여야 합니다.`,
  PRODUCT_176: COLUMN_NAME_REQUIRED,
  PRODUCT_177: tooLong(LIMITS.columnName),
  PRODUCT_178: REJECTED_CHARACTER,
  PRODUCT_179: tooLong(LIMITS.columnDescription),
  PRODUCT_180: REJECTED_CHARACTER,
  PRODUCT_184: TEMPLATE_REQUIRED,
  PRODUCT_186: tooLong(LIMITS.note),
  PRODUCT_187: REJECTED_CHARACTER,
  PRODUCT_188: `사이즈는 ${LIMITS.minRows}~${LIMITS.maxRows}개여야 합니다.`,
  PRODUCT_190: SIZE_LABEL_REQUIRED,
  PRODUCT_191: tooLong(LIMITS.sizeLabel),
  PRODUCT_192: REJECTED_CHARACTER,
  PRODUCT_197: VALUE_RULE,
};

function collect(
  fieldErrors: ApiError['fieldErrors'],
  keyOf: (path: string) => string,
): FormErrors {
  const errors: FormErrors = {};
  for (const item of fieldErrors) {
    const key = keyOf(item.field);
    const message = MESSAGES[item.code] ?? CHECK_FIELDS;
    if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
  }
  return withSummary(errors);
}

/** 템플릿 본문 경로(`columns[2].name`) → 폼 칸 키 */
function templateKey(path: string): string {
  if (path === 'name') return 'name';
  const column = /^columns\[(\d+)\]\.(name|description)$/.exec(path);
  if (column) return `columns.${column[1]}.${column[2]}`;
  if (path.startsWith('columns')) return 'columns';
  return 'form';
}

/** 상품 사이즈표 본문 경로 → 폼 칸 키. 칸 순서는 보낸 순서(= 화면 항목 순서)다 */
function chartKey(path: string, columns: readonly TemplateColumn[]): string {
  if (path === 'note' || path === 'templateId') return path;
  const cell = /^rows\[(\d+)\]\.cells\[(\d+)\]/.exec(path);
  if (cell) {
    const column = columns[Number(cell[2])];
    return column ? cellKey(Number(cell[1]), column.columnId) : 'rows';
  }
  const label = /^rows\[(\d+)\]\.sizeLabel$/.exec(path);
  if (label) return labelKey(Number(label[1]));
  if (path.startsWith('rows')) return 'rows';
  return 'form';
}

export type SaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 대상이 바뀌었거나(삭제 · 동시 저장 · 템플릿 변경) 결과를 모름 — 다시 읽고 안내한다 */
  | { kind: 'reload'; notice: string }
  | { kind: 'errors'; errors: FormErrors };

/** 401 은 폼을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내 */
function common(cause: unknown): SaveFailure | ApiError {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'PRODUCT_072') return { kind: 'errors', errors: { form: [BUSY] } };
  return cause;
}

/** 요청 제한(429)은 기본 문구가 운영자 문구다. 그 밖은 일반 실패 문구(서버 원문을 보이지 않는다) */
const fallback = (cause: ApiError): SaveFailure => ({
  kind: 'errors',
  errors: { form: [cause.kind === 'rateLimited' ? cause.message : SAVE_FAILED] },
});

/** 템플릿 만들기 · 고치기 실패 */
export function templateSaveFailure(cause: unknown): SaveFailure {
  const result = common(cause);
  if (!(result instanceof ApiError)) return result;
  if (result.fieldErrors.length > 0)
    return { kind: 'errors', errors: collect(result.fieldErrors, templateKey) };
  switch (result.code) {
    case 'PRODUCT_059':
      return { kind: 'reload', notice: TEMPLATE_GONE_NOTICE };
    case 'PRODUCT_060':
      return { kind: 'errors', errors: withSummary({ columns: [COLUMN_NAME_DUPLICATED] }) };
    case 'PRODUCT_062':
      return { kind: 'errors', errors: { form: [TEMPLATE_IN_USE_STRUCTURE] } };
  }
  // 061 · 071(순서 · 항목 아이디 중복)은 화면이 만들지 않는 본문이라 일반 실패다
  return fallback(result);
}

/** 템플릿 삭제 실패 — 지킬 입력이 없어 401 도 읽기처럼 로그인 안내로 보낸다 */
export function templateDeleteFailure(
  cause: unknown,
): SaveFailure | { kind: 'message'; message: string } {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: DELETE_FAILED };
  if (cause.code === 'PRODUCT_059') return { kind: 'reload', notice: TEMPLATE_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'PRODUCT_063') return { kind: 'message', message: TEMPLATE_IN_USE_DELETE };
  if (cause.code === 'PRODUCT_072') return { kind: 'message', message: BUSY };
  if (cause.kind === 'rateLimited') return { kind: 'message', message: cause.message };
  return { kind: 'message', message: DELETE_FAILED };
}

/**
 * 상품 사이즈표 저장 실패.
 * - 066 · 067 · 068(항목이 템플릿과 안 맞음)은 그사이 템플릿이 바뀐 것이다 — 템플릿을 다시 읽는다(`reload`).
 * - 073(다른 템플릿으로 먼저 저장됨)은 상품 표를 다시 읽는다. 마지막 쓰기가 이기는 계약이라 버전 충돌은 없다.
 */
export function chartSaveFailure(
  cause: unknown,
  columns: readonly TemplateColumn[],
): SaveFailure | { kind: 'templateChanged' } | { kind: 'productGone' } {
  const result = common(cause);
  if (!(result instanceof ApiError)) return result;
  if (result.fieldErrors.length > 0)
    return {
      kind: 'errors',
      errors: collect(result.fieldErrors, (path) => chartKey(path, columns)),
    };
  switch (result.code) {
    case 'PRODUCT_064':
      return { kind: 'productGone' };
    case 'PRODUCT_065':
      return { kind: 'errors', errors: withSummary({ templateId: [PICKED_TEMPLATE_GONE] }) };
    case 'PRODUCT_066':
    case 'PRODUCT_067':
    case 'PRODUCT_068':
      return { kind: 'templateChanged' };
    case 'PRODUCT_069':
      return { kind: 'errors', errors: withSummary({ rows: [SIZE_LABEL_DUPLICATED] }) };
    case 'PRODUCT_073':
      return { kind: 'reload', notice: CHART_CONFLICT_NOTICE };
  }
  return fallback(result);
}
