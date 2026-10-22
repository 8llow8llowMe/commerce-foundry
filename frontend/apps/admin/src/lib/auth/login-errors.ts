import { ApiError } from '@cf/api-client';

export type LoginErrors = { form: string[]; email: string[]; password: string[] };

export const NO_LOGIN_ERRORS: LoginErrors = { form: [], email: [], password: [] };

export const LOGIN_FAILED = '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/**
 * 관리자 로그인 실패를 화면 문구로(#204). 어드민은 국문만이라 서버 문구를 그대로 쓴다.
 * - 400 `AUTH_030` — 이메일 없음 · 비밀번호 틀림 · 고객 계정이 모두 같은 한 문구다(계정 존재 여부를 흘리지 않게). 칸을 짚지 않는다.
 * - 필드 검증(`AUTH_130~132`) — 이메일 · 비밀번호 칸에 붙인다. 그 밖의 칸은 폼 전체 한 문구다.
 * - 요청 제한(429) — 남은 시간을 담은 안내.
 * - 그 밖(연결 · 일시 장애 · 예상 밖) — 한 문구로 다시 시도를 권한다.
 */
export function loginErrors(cause: unknown): LoginErrors {
  if (!(cause instanceof ApiError)) return { ...NO_LOGIN_ERRORS, form: [LOGIN_FAILED] };
  if (cause.kind === 'rateLimited') {
    const wait =
      cause.retryAfterSec !== null && cause.retryAfterSec > 0
        ? `${cause.retryAfterSec}초 뒤에`
        : '잠시 뒤에';
    return { ...NO_LOGIN_ERRORS, form: [`요청이 많습니다. ${wait} 다시 시도해 주세요.`] };
  }
  if (cause.fieldErrors.length > 0) {
    const pick = (field: string) =>
      cause.fieldErrors.filter((item) => item.field === field).map((item) => item.message);
    const other = cause.fieldErrors.some(
      (item) => item.field !== 'email' && item.field !== 'password',
    );
    return { email: pick('email'), password: pick('password'), form: other ? [LOGIN_FAILED] : [] };
  }
  if (cause.code === 'AUTH_030') return { ...NO_LOGIN_ERRORS, form: [cause.message] };
  return { ...NO_LOGIN_ERRORS, form: [LOGIN_FAILED] };
}
