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
  CATEGORY_INVALID,
  DESCRIPTION_INVALID,
  LABEL_INVALID,
  PATH_INVALID,
  STRUCTURE_INVALID,
  TOO_DEEP,
  TOO_MANY_ITEMS,
  URL_HOST_NOT_ALLOWED,
} from './form';
import { VERSION_CONFLICT, type NavDetail, type NavItemPayload } from './types';

// 메뉴 쓰기(초안 저장 · 게시) 실패 → 화면 처리. 코드 정본은 `backend/docs/store-navigation-guide.md` "오류·감사".
// 서버 원문 대신 업무 문구를 보인다(푸터와 같은 규칙).

export const CHECK_FIELDS = '입력값을 확인해 주세요.';
export const CONFLICT_NOTICE =
  '다른 관리자가 메뉴를 먼저 저장했습니다. 입력은 그대로 두고 최신 상태를 다시 불러왔습니다. 최신 내용을 불러오면 지금 변경은 사라집니다. 확인한 뒤 내 입력으로 다시 저장할 수도 있습니다.';
export const UNKNOWN_OUTCOME_NOTICE =
  '처리 결과를 확인하지 못해 최신 상태를 다시 불러왔습니다. 공개 상태 문구로 반영됐는지 확인한 뒤 필요하면 다시 시도해 주세요.';
export const PLACEMENT_INVALID =
  'PC 1차 메뉴 · 모바일 바로가기는 최상위 메뉴에서만 켤 수 있습니다.';
export const PUBLISH_HOST_REMOVED =
  '게시할 초안의 다른 사이트 링크 중 지금은 허용되지 않은 사이트가 있습니다. 그 링크를 고치거나 지운 초안을 저장한 뒤 게시해 주세요.';

/** 서버 코드 → 문구(칸 정보가 없거나 칸 오류의 code 로 올 때) */
const CODE_MESSAGES: Record<string, string> = {
  STORE_NAV_102: TOO_MANY_ITEMS,
  STORE_NAV_103: STRUCTURE_INVALID,
  STORE_NAV_104: STRUCTURE_INVALID,
  STORE_NAV_105: STRUCTURE_INVALID,
  STORE_NAV_106: TOO_DEEP,
  STORE_NAV_107: STRUCTURE_INVALID,
  STORE_NAV_108: LABEL_INVALID,
  STORE_NAV_110: CATEGORY_INVALID,
  STORE_NAV_111: STRUCTURE_INVALID,
  STORE_NAV_112: STRUCTURE_INVALID,
  STORE_NAV_114: DESCRIPTION_INVALID,
  STORE_NAV_115: PLACEMENT_INVALID,
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
 * URL 거절(`STORE_NAV_109`, 칸 정보 없음). 화면 검사를 통과한 뒤라면 다른 사이트 링크의 허용 사이트 문제다 —
 * 보낸 본문에서 다른 사이트(EXTERNAL) 항목의 주소 칸에 모두 붙인다(어느 항목인지 서버가 알려 주지 않는다).
 * 다른 사이트 항목이 없으면 경로 형식 문제로 폼 전체에 둔다.
 */
function urlRejected(items: readonly NavItemPayload[], publishing: boolean): FormErrors {
  const errors: FormErrors = {};
  items.forEach((item, index) => {
    if (item.type === 'EXTERNAL') errors[`items.${index}.url`] = [URL_HOST_NOT_ALLOWED];
  });
  if (Object.keys(errors).length === 0) return { form: [PATH_INVALID] };
  errors.form = [publishing ? PUBLISH_HOST_REMOVED : URL_HOST_NOT_ALLOWED];
  return errors;
}

/**
 * 초안 저장 · 게시 실패. `sent` 는 저장이면 보낸 items, 게시면 게시하려던 저장본의 items 다(109 를 칸에 붙인다).
 * - 401 은 입력을 지키고 다시 로그인을 안내한다(`SESSION_EXPIRED_ON_SAVE`). 403 은 권한 안내.
 * - 409 `STORE_NAV_020` 과 결과 모름(502 · 504)은 다시 읽는다. 초안 저장은 멱등이 아니라(같은 본문도 새 리비전)
 *   **자동으로 다시 보내지 않는다.** 버전(101) · 리비전 번호(113) 오류도 화면의 기준이 틀어졌다는 뜻이라 다시 읽는다.
 */
export function writeFailure(
  cause: unknown,
  sent: readonly NavItemPayload[],
  publishing = false,
): WriteFailure {
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
    case 'STORE_NAV_101':
    case 'STORE_NAV_113':
      return { kind: 'reload', notice: CONFLICT_NOTICE, conflict: true };
    case 'STORE_NAV_109':
      return { kind: 'errors', errors: urlRejected(sent, publishing) };
  }
  const known = cause.code ? CODE_MESSAGES[cause.code] : undefined;
  if (known) return { kind: 'errors', errors: { form: [known] } };
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

/** 게시가 경합 · 결과 모름으로 끝나 다시 읽었더니 다른 저장본이 최신이다 — 화면을 그 저장본으로 바꾼다 */
export const publishReloadedNotice = (latestRevision: number) =>
  `다른 관리자가 새 초안을 저장해 최신 ${latestRevision}번을 불러왔습니다. 확인한 뒤 다시 게시해 주세요.`;
/** 다시 읽은 최신이 게시하려던 번호와 같은데 공개되지 않았다(결과 모름 · 버전 경합) */
export const publishNotAppliedNotice = (revisionNumber: number) =>
  `${revisionNumber}번이 게시되지 않았습니다. 최신 상태를 다시 불러왔으니 확인한 뒤 다시 게시해 주세요.`;

/**
 * 게시 실패 뒤 판정. `writeFailure` 가 다시 읽기(409 · 결과 모름 · 버전 · 리비전 오류)로 본 실패만 다시 읽은 상세(`reloaded`)로 가른다.
 * - 다시 읽은 `publishedRevision` 이 요청 번호와 같으면 성공(같은 리비전 동시 게시의 한쪽 409 · 응답 유실 — BE 계약).
 * - 아니면 화면을 다시 읽은 저장본으로 바꾼다(`resetTree`). 게시는 저장한 초안만 하므로 화면에 지킬 입력이 없다 —
 *   남겨 두면 "저장하지 않은 변경" 으로 잘못 보인다. 저장(PUT) 실패의 409 는 이 함수를 쓰지 않는다(입력을 지킨다).
 * 다시 읽기가 필요 없는 실패(권한 · 입력 오류)는 `resetTree: false` 로 그대로 돌려준다.
 */
export type PublishOutcome = { kind: 'done' } | (WriteFailure & { resetTree: boolean });

/** 이 게시 실패가 다시 읽어 봐야 하는 것인가 */
export const publishNeedsReload = (cause: unknown, sent: readonly NavItemPayload[]) =>
  writeFailure(cause, sent, true).kind === 'reload';

export function publishOutcome(
  cause: unknown,
  reloaded: NavDetail | null,
  revisionNumber: number,
  sent: readonly NavItemPayload[],
): PublishOutcome {
  const failure = writeFailure(cause, sent, true);
  if (failure.kind !== 'reload') return { ...failure, resetTree: false };
  if (reloaded && reloaded.publishedRevision === revisionNumber) return { kind: 'done' };
  const notice =
    reloaded && reloaded.latestRevision !== revisionNumber
      ? publishReloadedNotice(reloaded.latestRevision)
      : publishNotAppliedNotice(revisionNumber);
  return { kind: 'reload', notice, conflict: false, resetTree: true };
}

/** 게시 실패 뒤 다시 읽기도 실패했다 — 공개 상태를 모른다 */
export const PUBLISH_RELOAD_FAILED =
  '게시 결과를 확인하지 못했습니다. 화면을 새로 고쳐 공개 상태를 확인한 뒤 필요하면 다시 게시해 주세요.';
