import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  FORBIDDEN_INLINE,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';
import { addError } from '@/lib/product/form-text';
import {
  IMAGE_DUPLICATED,
  IMAGES_TOO_MANY,
  INVALID_CHARACTER,
  LIST_FULL,
  LIST_ITEM_INVALID,
  MEASUREMENTS_INVALID_CHARACTER,
  MEASUREMENTS_NOTE_MAX,
  NAME_HINT_MAX,
  ORIGIN_MAX,
  TONE_MAX,
  tooLong,
} from './form';

// AI 상품 초안 요청 · 거절 실패 → 화면 처리(#360). 서버 문구는 칸 이름이 영문이라(`nameHint 는 …`) 검증은 코드별 운영자 문구를 쓴다.
// 코드 정본은 BE `ProductDraftErrorCode` · `ProductDraftValidationMessage` 다.

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const BODY_UNREADABLE =
  '요청 내용을 읽지 못했습니다. 화면을 새로고침한 뒤 다시 입력해 주세요.';
export const IMAGE_NOT_ALLOWED =
  '이 화면에서 올린 사진만 넣을 수 있습니다. 사진을 빼고 다시 올려 주세요.';
export const IMAGE_NOT_FOUND = '올린 사진을 찾지 못했습니다. 사진을 빼고 다시 올려 주세요.';
export const IMAGE_FORMAT = 'JPG · PNG · WebP 형식의 5MB 이하 사진만 넣을 수 있습니다.';
export const THUMBNAIL_DUPLICATED = '대표 사진은 한 장만 넣을 수 있습니다.';
export const MEDIA_NOT_CONFIGURED =
  '사진 저장소가 아직 연결되지 않아 사진을 받을 수 없습니다. 사진을 빼면 요청할 수 있습니다.';
export const IMAGE_STORAGE_UNAVAILABLE =
  '사진 저장소를 확인하지 못해 요청하지 않았습니다. 잠시 후 다시 요청해 주세요.';
/**
 * 결과를 모르는 실패. 초안 요청에는 멱등 키가 없어 다시 보내면 초안이 하나 더 생긴다 — 상품은 생기지 않고 쓰지 않은 초안은 7일 뒤 만료된다.
 * 초안 목록이 아직 없어 확인할 곳이 없다 — 운영자가 알고 다시 보내게 한다.
 */
export const UNKNOWN_OUTCOME =
  '요청 결과를 확인하지 못했습니다. 초안이 이미 만들어졌을 수 있습니다. 다시 요청하면 초안이 하나 더 생기지만 상품에는 영향이 없고, 쓰지 않은 초안은 7일 뒤 만료됩니다.';

/** 검증 코드 → 문구. 칸은 서버 경로(`fieldErrors.field`)로 찾는다 */
const VALIDATION: Record<string, string> = {
  PRODUCT_DRAFT_101: tooLong(NAME_HINT_MAX),
  PRODUCT_DRAFT_102: INVALID_CHARACTER,
  PRODUCT_DRAFT_103: '카테고리를 다시 골라 주세요.',
  PRODUCT_DRAFT_104: LIST_FULL,
  PRODUCT_DRAFT_105: LIST_ITEM_INVALID,
  PRODUCT_DRAFT_106: LIST_FULL,
  PRODUCT_DRAFT_107: LIST_ITEM_INVALID,
  PRODUCT_DRAFT_108: tooLong(MEASUREMENTS_NOTE_MAX),
  PRODUCT_DRAFT_109: MEASUREMENTS_INVALID_CHARACTER,
  PRODUCT_DRAFT_110: tooLong(ORIGIN_MAX),
  PRODUCT_DRAFT_111: INVALID_CHARACTER,
  PRODUCT_DRAFT_112: LIST_FULL,
  PRODUCT_DRAFT_113: LIST_ITEM_INVALID,
  PRODUCT_DRAFT_114: tooLong(TONE_MAX),
  PRODUCT_DRAFT_115: INVALID_CHARACTER,
  PRODUCT_DRAFT_116: LIST_FULL,
  PRODUCT_DRAFT_117: LIST_ITEM_INVALID,
  PRODUCT_DRAFT_118: LIST_FULL,
  PRODUCT_DRAFT_119: LIST_ITEM_INVALID,
  PRODUCT_DRAFT_120: IMAGES_TOO_MANY,
  PRODUCT_DRAFT_121: IMAGE_NOT_ALLOWED,
  PRODUCT_DRAFT_122: IMAGE_NOT_ALLOWED,
  PRODUCT_DRAFT_123: IMAGE_NOT_ALLOWED,
  PRODUCT_DRAFT_124: IMAGE_NOT_ALLOWED,
  PRODUCT_DRAFT_125: IMAGE_NOT_ALLOWED,
  PRODUCT_DRAFT_126: IMAGE_NOT_ALLOWED,
  PRODUCT_100: BODY_UNREADABLE,
};

/** 이미지 업무 오류(저장 전에 난다 — 503 이어도 초안은 생기지 않았다) */
const IMAGE_ERRORS: Record<string, { key: 'images' | 'form'; message: string }> = {
  PRODUCT_DRAFT_030: { key: 'images', message: IMAGE_NOT_ALLOWED },
  PRODUCT_DRAFT_031: { key: 'images', message: IMAGE_NOT_ALLOWED },
  PRODUCT_DRAFT_033: { key: 'images', message: IMAGE_NOT_FOUND },
  PRODUCT_DRAFT_034: { key: 'images', message: THUMBNAIL_DUPLICATED },
  PRODUCT_DRAFT_035: { key: 'images', message: IMAGE_DUPLICATED },
  PRODUCT_DRAFT_036: { key: 'images', message: IMAGE_FORMAT },
  PRODUCT_DRAFT_032: { key: 'form', message: MEDIA_NOT_CONFIGURED },
  PRODUCT_DRAFT_501: { key: 'form', message: IMAGE_STORAGE_UNAVAILABLE },
};

const FORM_FIELDS = new Set([
  'nameHint',
  'categoryId',
  'measurementsNote',
  'origin',
  'tone',
  'materials',
  'careNotes',
  'certifications',
  'mustInclude',
  'mustAvoid',
  'images',
]);

/** 서버 경로 → 폼 칸 키. 목록 원소(`materials[2]`) · 사진 칸(`images[0].url`)은 그 목록 칸, 모르는 경로는 폼 전체 */
export function draftFieldKey(path: string): string {
  const head = fieldKey(path).split('.')[0] ?? '';
  // 목록 원소 제약의 경로는 `materials[2].<list element>` 처럼 끝에 붙는다 — 첫 마디만 본다
  const name = head.replace(/<.*$/, '');
  return FORM_FIELDS.has(name) ? name : 'form';
}

export type DraftCreateFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'errors'; errors: FormErrors; uncertain: boolean };

const errorsOnly = (errors: FormErrors): DraftCreateFailure => ({
  kind: 'errors',
  errors,
  uncertain: false,
});

/** 서버가 처리했는지 모르는 실패 — 연결 실패 · 봉투 아님 · 502 · 503 · 504 · 5xx (이미지 503 `032` · `501` 은 앞에서 거른다) */
function isUncertain(cause: unknown): boolean {
  if (!(cause instanceof ApiError)) return true;
  if (
    cause.kind === 'network' ||
    cause.kind === 'invalidResponse' ||
    cause.kind === 'unknownOutcome' ||
    cause.kind === 'unavailable'
  ) {
    return true;
  }
  return cause.status !== null && cause.status >= 500;
}

/**
 * 초안 요청 실패를 화면 처리로 가른다. 폼 입력은 언제나 지킨다.
 * 401 은 폼을 지키고 다시 로그인을 안내한다, 403 은 접근 상태다. 결과를 모르는 실패는 `uncertain`(다시 보내면 초안이 둘이다).
 */
export function draftCreateFailure(cause: unknown): DraftCreateFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return errorsOnly({ form: [SESSION_EXPIRED_ON_SAVE] });
  if (denied) return { kind: 'access', denied };

  const image = cause instanceof ApiError && cause.code ? IMAGE_ERRORS[cause.code] : undefined;
  if (image) return errorsOnly({ [image.key]: [image.message] });
  if (!(cause instanceof ApiError) || isUncertain(cause)) {
    return { kind: 'errors', errors: { form: [UNKNOWN_OUTCOME] }, uncertain: true };
  }

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const key = draftFieldKey(item.field);
      addError(errors, key, VALIDATION[item.code] ?? CHECK_FIELDS);
    }
    return errorsOnly(errors);
  }
  const known = cause.code ? VALIDATION[cause.code] : undefined;
  if (known) return errorsOnly({ form: [known] });
  if (cause.kind === 'rateLimited') return errorsOnly({ form: [cause.message] });
  return errorsOnly({ form: [SAVE_FAILED] });
}

// ---------- 거절 ----------

export const REJECT_NOT_FOUND = '초안을 찾지 못했습니다. 새로고침해 주소를 확인해 주세요.';
export const REJECT_APPLIED = '이미 상품에 적용한 초안이라 거절할 수 없습니다.';
export const REJECT_EXPIRED = '보관 기간이 지난 초안이라 거절할 필요가 없습니다.';
export const REJECT_BUSY = '다른 변경과 겹쳤습니다. 새로고침해 지금 상태를 확인해 주세요.';
export const REJECT_SESSION_EXPIRED =
  '로그인이 만료되어 거절하지 못했습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 눌러 주세요.';
/** 거절은 이미 거절한 초안에 다시 보내도 200 이다 — 결과를 모르면 다시 눌러도 된다 */
export const REJECT_UNKNOWN =
  '거절 결과를 확인하지 못했습니다. 새로고침해 상태를 확인하거나 다시 눌러 주세요.';

/** 거절 실패 → 안내 문구와 화면을 다시 읽을지(상태가 바뀌었을 때) */
export function rejectFailure(cause: unknown): { message: string; refresh: boolean } {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { message: REJECT_SESSION_EXPIRED, refresh: false };
  if (denied) return { message: FORBIDDEN_INLINE, refresh: false };
  if (cause instanceof ApiError) {
    switch (cause.code) {
      case 'PRODUCT_DRAFT_001':
      case 'PRODUCT_DRAFT_127':
        return { message: REJECT_NOT_FOUND, refresh: false };
      case 'PRODUCT_DRAFT_020':
        return { message: REJECT_APPLIED, refresh: true };
      case 'PRODUCT_DRAFT_022':
        return { message: REJECT_EXPIRED, refresh: true };
      case 'PRODUCT_DRAFT_025':
        return { message: REJECT_BUSY, refresh: true };
      default:
        break;
    }
    if (cause.kind === 'notFound') return { message: REJECT_NOT_FOUND, refresh: false };
    if (cause.kind === 'rateLimited') return { message: cause.message, refresh: false };
  }
  return { message: REJECT_UNKNOWN, refresh: false };
}

// ---------- 생성 시작 · 결과 확인 ----------

export const GENERATION_SESSION_EXPIRED =
  '로그인이 만료되어 요청하지 못했습니다. 새 탭에서 어드민에 다시 로그인한 뒤 다시 눌러 주세요.';
/**
 * 결과를 모르는 실패. 생성 시작은 같은 회차를 재시도하고(이미 생성 중이면 200), 결과 확인은 저장된 결과를 다시 읽을 뿐이라
 * 다시 눌러도 생성이 두 번 걸리지 않는다.
 */
export const GENERATION_UNKNOWN =
  '처리 결과를 확인하지 못했습니다. 새로고침해 상태를 확인하거나 잠시 후 다시 눌러 주세요. 다시 눌러도 생성이 두 번 걸리지 않습니다.';

/** 서버 문구가 운영자 문구인 코드(BE `ProductDraftErrorCode`) — 상태가 바뀌었으면 화면을 다시 읽는다 */
const GENERATION_CODES: Record<string, { refresh: boolean }> = {
  PRODUCT_DRAFT_022: { refresh: true }, // 보관 만료
  PRODUCT_DRAFT_025: { refresh: true }, // 동시 변경
  PRODUCT_DRAFT_037: { refresh: false }, // 이미지 합계 20MB 초과 — 새 초안으로만 해결
  PRODUCT_DRAFT_050: { refresh: false }, // 생성 꺼짐
  PRODUCT_DRAFT_051: { refresh: true }, // 시작할 수 없는 상태
  PRODUCT_DRAFT_052: { refresh: true }, // 생성할 수 없는 초안
  PRODUCT_DRAFT_502: { refresh: false }, // 생성 서비스 장애
  PRODUCT_DRAFT_504: { refresh: false }, // 결과 확인 불가
};

/**
 * 결과 확인의 503 `502` — operations 결과 조회 장애다. 서버 문구("생성을 시작하지 못했습니다")는 생성 등록 실패용이라
 * 이미 생성 중인 초안에서는 틀린 말이 된다(운영자가 새 초안을 또 요청하게 된다).
 */
export const REFRESH_UNAVAILABLE =
  '결과를 가져오지 못했습니다. 생성은 계속 진행 중이니 잠시 뒤 다시 확인해 주세요.';
/** 결과 확인을 눌렀는데 작업이 아직 끝나지 않았다(서버는 `PENDING` 을 저장하지 않고 그대로 `GENERATING` 을 돌려준다) */
export const STILL_GENERATING = '아직 만들고 있습니다. 잠시 뒤 다시 확인해 주세요.';

/** 생성 시작(`/generate`) · 결과 확인(`/refresh`) 실패 → 안내 문구와 화면을 다시 읽을지 */
export function generationFailure(
  cause: unknown,
  action: 'generate' | 'refresh' = 'generate',
): { message: string; refresh: boolean } {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { message: GENERATION_SESSION_EXPIRED, refresh: false };
  if (denied) return { message: FORBIDDEN_INLINE, refresh: false };
  if (cause instanceof ApiError) {
    if (action === 'refresh' && cause.code === 'PRODUCT_DRAFT_502')
      return { message: REFRESH_UNAVAILABLE, refresh: false };
    const known = cause.code ? GENERATION_CODES[cause.code] : undefined;
    if (known) return { message: cause.message, refresh: known.refresh };
    if (cause.code === 'PRODUCT_DRAFT_001' || cause.code === 'PRODUCT_DRAFT_127')
      return { message: REJECT_NOT_FOUND, refresh: false };
    if (cause.kind === 'notFound') return { message: REJECT_NOT_FOUND, refresh: false };
    if (cause.kind === 'rateLimited') return { message: cause.message, refresh: false };
  }
  return { message: GENERATION_UNKNOWN, refresh: false };
}
