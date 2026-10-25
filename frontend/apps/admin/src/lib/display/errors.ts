import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import {
  BODY_CHARACTER,
  LABEL_WITHOUT_URL,
  LINK_RULE,
  PERIOD_FORMAT,
  PERIOD_ORDER,
  PRODUCT_DUPLICATED,
  productCountMessage,
  SINGLE_LINE_ONLY,
  TOO_LONG,
} from './form';
import { LIMITS, type SectionTypeCode } from './types';

// 메인 전시 쓰기 실패 → 화면 처리. 운영자에게는 서버 문구(칸 이름 · 유형 코드가 영문으로 들어 있다)를 그대로 보이지 않고
// 코드별 업무 문구를 쓴다(#305). 코드 정본은 BE `DisplayErrorCode` · `DisplayValidationMessage`.

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const SECTION_GONE_NOTICE = '다른 곳에서 삭제된 섹션입니다. 목록을 다시 불러왔습니다.';
export const UNKNOWN_OUTCOME_NOTICE =
  '저장 결과를 확인하지 못해 목록을 다시 불러왔습니다. 반영됐는지 확인해 주세요.';
export const BUSY = '다른 관리자가 메인 전시를 바꾸는 중입니다. 잠시 후 다시 시도해 주세요.';
export const ORDER_CHANGED_NOTICE =
  '그사이 다른 곳에서 섹션이 추가되거나 삭제되어 순서를 저장하지 못했습니다. 목록을 다시 불러왔으니 순서를 다시 정해 주세요.';
export const MEDIA_STORAGE_UNAVAILABLE =
  '이미지 · 영상 저장소 설정이 맞지 않아 이미지 · 영상이 있는 섹션을 지금 저장할 수 없습니다. 개발 담당자에게 문의해 주세요.';
export const SECTION_LIMIT = `섹션은 최대 ${LIMITS.sections}개까지 만들 수 있습니다. 쓰지 않는 섹션을 지운 뒤 다시 시도해 주세요.`;
export const ITEM_NOT_ALLOWED =
  '이 섹션에 넣을 수 없는 항목이 있습니다. 목록을 다시 불러온 뒤 확인해 주세요.';
export const PRODUCT_MISSING = '찾을 수 없는 상품이 있습니다. 그 상품을 빼고 저장해 주세요.';
export const DELETE_FAILED = '삭제하지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const TOGGLE_FAILED =
  '노출을 바꾸지 못했습니다. 수정 화면에서 내용을 확인한 뒤 다시 시도해 주세요.';

/** 요청 검증 코드 → [폼 칸, 문구]. 칸 키는 폼(`SectionForm`)의 칸이다 */
const VALIDATION: Record<string, [string, string]> = {
  PRODUCT_141: ['title', TOO_LONG.title],
  PRODUCT_142: ['subtitle', TOO_LONG.subtitle],
  PRODUCT_143: ['body', TOO_LONG.body],
  PRODUCT_144: ['linkUrl', TOO_LONG.linkUrl],
  PRODUCT_145: ['linkLabel', TOO_LONG.linkLabel],
  PRODUCT_165: ['period', PERIOD_FORMAT],
};

/** 서버 칸 경로(`items[2].media.altText`) → 폼 칸 키. 아이템은 미디어 → 상품 순으로 보낸다(`sectionRequest`) */
function formKey(path: string, mediaCount: number): string {
  if (/^(startAt|endAt|periodInRange)$/.test(path)) return 'period';
  if (/^(title|subtitle|body|linkUrl|linkLabel)$/.test(path)) return path;
  const item = /^items\[(\d+)\]/.exec(path);
  if (item) {
    const index = Number(item[1]);
    if (index < mediaCount) return path.endsWith('.altText') ? `media.${index}.altText` : 'media';
    return 'products';
  }
  if (path === 'items') return mediaCount > 0 ? 'media' : 'products';
  return 'form';
}

function fieldMessage(code: string, key: string): string {
  if (code === 'PRODUCT_164') {
    return key === 'body' ? BODY_CHARACTER : SINGLE_LINE_ONLY;
  }
  if (code === 'PRODUCT_154') return TOO_LONG.altText;
  return VALIDATION[code]?.[1] ?? CHECK_FIELDS;
}

/** 칸 오류만 있으면 저장 버튼 위에 요약을 둔다 — 오류 칸이 화면 밖일 수 있다 */
export function withSummary(errors: FormErrors): FormErrors {
  if (errors.form || Object.keys(errors).length === 0) return errors;
  return { ...errors, form: [CHECK_FIELDS] };
}

export type SaveFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 대상이 없어졌거나(404) 결과를 모름(502 · 504) — 목록을 다시 읽고 안내한다 */
  | { kind: 'reload'; notice: string }
  | { kind: 'errors'; errors: FormErrors };

/**
 * 생성 · 수정 실패를 화면 처리로 가른다.
 * - 401 은 폼을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - fieldErrors 는 칸에, 섹션 규칙 오류(`PRODUCT_047~056`)는 그 칸 · 폼 전체에 업무 문구로 붙인다.
 * - 버전이 없어 마지막 쓰기가 이긴다. 409 는 잠금 대기(`PRODUCT_058`) · 섹션 상한(`PRODUCT_056`)뿐이다.
 */
export function saveFailure(
  cause: unknown,
  context: { type: SectionTypeCode; mediaCount: number },
): SaveFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.code === 'PRODUCT_046') return { kind: 'reload', notice: SECTION_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const code = item.code;
      const key = VALIDATION[code]?.[0] ?? formKey(item.field, context.mediaCount);
      const message = fieldMessage(code, key);
      if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
    }
    return { kind: 'errors', errors: withSummary(errors) };
  }

  const itemsKey = context.type === 'PRODUCT_PICK' ? 'products' : 'media';
  switch (cause.code) {
    case 'PRODUCT_047':
      return { kind: 'errors', errors: { form: [ITEM_NOT_ALLOWED] } };
    case 'PRODUCT_048':
      return {
        kind: 'errors',
        errors: withSummary({
          [itemsKey]: [
            context.type === 'PRODUCT_PICK'
              ? productCountMessage(context.type)
              : '이미지 · 영상 수가 이 섹션이 받을 수 있는 범위를 벗어났습니다.',
          ],
        }),
      };
    case 'PRODUCT_049':
      return { kind: 'errors', errors: withSummary({ products: [PRODUCT_DUPLICATED] }) };
    case 'PRODUCT_050':
      return { kind: 'errors', errors: withSummary({ products: [PRODUCT_MISSING] }) };
    case 'PRODUCT_051':
      return { kind: 'errors', errors: withSummary({ period: [PERIOD_ORDER] }) };
    case 'PRODUCT_052':
      return { kind: 'errors', errors: withSummary({ linkUrl: [LINK_RULE] }) };
    case 'PRODUCT_053':
      return { kind: 'errors', errors: withSummary({ linkLabel: [LABEL_WITHOUT_URL] }) };
    case 'PRODUCT_054':
    case 'PRODUCT_055':
      return { kind: 'errors', errors: { form: [MEDIA_STORAGE_UNAVAILABLE] } };
    case 'PRODUCT_056':
      return { kind: 'errors', errors: { form: [SECTION_LIMIT] } };
    case 'PRODUCT_058':
      return { kind: 'errors', errors: { form: [BUSY] } };
  }
  // 요청 제한(429)은 기본 문구가 운영자 문구다. 그 밖은 일반 실패 문구(서버 원문을 보이지 않는다)
  if (cause.kind === 'rateLimited') return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

export type RowFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'reload'; notice: string }
  | { kind: 'message'; message: string };

/**
 * 목록 줄의 쓰기(노출 켜기 · 끄기, 삭제) 실패. 401 은 지킬 입력이 없어 읽기처럼 로그인 화면으로 보낸다.
 * 노출 바꾸기는 섹션 전체를 다시 보내므로 저장 실패와 같은 코드가 올 수 있다 — 칸 오류는 수정 화면에서 보라고 안내한다.
 */
export function rowFailure(cause: unknown, fallback: string): RowFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: fallback };
  if (cause.code === 'PRODUCT_046') return { kind: 'reload', notice: SECTION_GONE_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'PRODUCT_058') return { kind: 'message', message: BUSY };
  if (cause.code === 'PRODUCT_054' || cause.code === 'PRODUCT_055') {
    return { kind: 'message', message: MEDIA_STORAGE_UNAVAILABLE };
  }
  if (cause.kind === 'rateLimited') return { kind: 'message', message: cause.message };
  return { kind: 'message', message: fallback };
}

export type OrderFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 섹션 집합이 바뀌었거나(400 `PRODUCT_057`) 결과를 모름 — 다시 읽는다(바꾼 순서는 버린다) */
  | { kind: 'reload'; notice: string }
  /** 바꾼 순서를 그대로 두고 안내한다 — 다시 저장할 수 있다 */
  | { kind: 'message'; message: string };

/** 순서 저장 실패. 401 은 바꾼 순서(입력)를 지키려고 화면에 둔 채 다시 로그인을 안내한다 */
export function orderFailure(cause: unknown): OrderFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'message', message: SESSION_EXPIRED_ON_SAVE };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'message', message: SAVE_FAILED };
  if (cause.code === 'PRODUCT_057') return { kind: 'reload', notice: ORDER_CHANGED_NOTICE };
  if (cause.kind === 'unknownOutcome') return { kind: 'reload', notice: UNKNOWN_OUTCOME_NOTICE };
  if (cause.code === 'PRODUCT_058') return { kind: 'message', message: BUSY };
  if (cause.kind === 'rateLimited') return { kind: 'message', message: cause.message };
  return { kind: 'message', message: SAVE_FAILED };
}
