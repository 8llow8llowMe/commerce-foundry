import { ApiError } from '@cf/api-client';
import { accessDeniedOf, SAVE_FAILED, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { ADMIN_ACCOUNTS_PATH } from './query';

/**
 * 운영자 비밀번호 재설정(#237, BE #589 — `service/auth-service/docs/admin-password-reset.md`).
 * 대표 관리자가 관리자 계정 화면에서 링크 발송을 요청하면, 서버가 설정 URL(이 앱의 `/password-reset`)에 `#token=…` 을 붙여 메일로 보낸다.
 * 토큰은 fragment 로만 오가고(서버 로그 · Referer 에 실리지 않는다) 화면은 읽은 즉시 주소에서 지운다.
 */
export const PASSWORD_RESET_PAGE = '/password-reset';
/** 공개 API(`permitAll`). 토큰을 본문으로 주지 않아 일반 BFF 프록시로 중계한다 */
export const PASSWORD_RESET_API = '/api/v1/auth/admin/password-reset';

/** 링크 발송 경로. 아이디는 서버가 준 문자열 그대로다(Snowflake) */
export function passwordResetLinkPath(memberId: string): string {
  return `${ADMIN_ACCOUNTS_PATH}/${encodeURIComponent(memberId)}/password-reset-link`;
}

// 서버가 만드는 토큰은 256비트 난수의 base64url 43자다. 모양이 다르면 보내지 않고 "링크가 올바르지 않다" 로 둔다
const TOKEN_PATTERN = /^[A-Za-z0-9_-]{43}$/;

/** `#token=…` → 토큰. 없거나 모양이 다르면 null */
export function tokenFromHash(hash: string): string | null {
  const token = new URLSearchParams(hash.replace(/^#/, '')).get('token');
  return token !== null && TOKEN_PATTERN.test(token) ? token : null;
}

/**
 * 발송 요청을 받았다는 문구. 서버는 대상 없음 · 비활성 · 메일 장애도 같은 성공으로 답하므로
 * 발송됐다고 단정하지 않는다(BE 계약 "FE는 발송 여부를 확정하는 문구를 표시하지 않는다").
 */
export function resetLinkRequested(name: string): string {
  return `${name} 님의 비밀번호 재설정 링크 발송을 요청했습니다. 활성 관리자라면 등록된 이메일로 링크가 갑니다. 메일이 오지 않으면 잠시 뒤 다시 요청해 주세요.`;
}

export const RESET_LINK_FAILED = '재설정 링크를 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const RESET_LINK_UNKNOWN =
  '요청 결과를 확인하지 못했습니다. 메일이 오지 않으면 잠시 뒤 다시 요청해 주세요.';

export type ResetLinkFailure =
  { kind: 'access'; denied: AccessDenied } | { kind: 'row'; message: string };

/**
 * 발송 요청 실패. 403 `AUTH_046`(대표 관리자가 아님)은 권한 안내, 429 `AUTH_062`(15분 창 초과) · 503 `AUTH_063`(기능 꺼짐)은 서버 문구,
 * 연결 실패 · 5xx 는 한 문구다. 결과를 모르는 실패(502 · 504)는 메일이 갈 수도 있어 다시 요청하기 전에 메일을 확인하게 한다.
 */
export function resetLinkFailure(cause: unknown): ResetLinkFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'row', message: RESET_LINK_FAILED };
  if (cause.kind === 'unknownOutcome') return { kind: 'row', message: RESET_LINK_UNKNOWN };
  if (cause.code === 'AUTH_062' || cause.code === 'AUTH_063') {
    return { kind: 'row', message: cause.message };
  }
  return { kind: 'row', message: RESET_LINK_FAILED };
}

/** 링크가 만료 · 위조 · 사용됨이거나 다른 변경과 겹쳐 더는 쓸 수 없다 — 새 링크가 필요하다 */
export const RESET_LINK_SPENT =
  '이 재설정 링크는 쓸 수 없습니다. 만료됐거나 이미 사용한 링크일 수 있습니다. 대표 관리자에게 새 링크를 요청해 주세요.';
export const RESET_UNKNOWN =
  '결과를 확인하지 못했습니다. 새 비밀번호로 로그인해 보고, 안 되면 대표 관리자에게 새 링크를 요청해 주세요.';

const RESET_FIELDS = new Set(['newPassword']);

export type ResetFailure =
  /** 이 링크로는 더 진행할 수 없다(400 `AUTH_061` · 409 `AUTH_064` · 결과 모름) — 폼을 거두고 문구만 둔다 */
  { kind: 'spent'; message: string } | { kind: 'errors'; errors: FormErrors };

/**
 * 재설정 실패. 서버는 유효한 링크 하나당 한 요청만 다음 단계로 보내므로(`GETDEL`), 토큰을 거친 실패는 같은 링크로 다시 보내지 않는다.
 * fieldErrors 는 새 비밀번호 칸에, 429 `AUTH_062` · 503 `AUTH_013` · `AUTH_063` 은 서버 문구로 폼에 둔다(토큰 확인 전 거절이라 다시 보낼 수 있다).
 */
export function resetFailure(cause: unknown): ResetFailure {
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.kind === 'unknownOutcome') return { kind: 'spent', message: RESET_UNKNOWN };
  if (cause.code === 'AUTH_061' || cause.code === 'AUTH_064') {
    return { kind: 'spent', message: RESET_LINK_SPENT };
  }
  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const target = RESET_FIELDS.has(item.field) ? item.field : 'form';
      const message = target === 'form' ? SAVE_FAILED : item.message;
      if (!(errors[target] ??= []).includes(message)) errors[target].push(message);
    }
    return { kind: 'errors', errors };
  }
  if (cause.code === 'AUTH_062' || cause.code === 'AUTH_063' || cause.code === 'AUTH_013') {
    return { kind: 'errors', errors: { form: [cause.message] } };
  }
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}
