import { ApiError } from '@cf/api-client';
import { formatWait } from './login-cooldown';

export type LoginErrors = { form: string[]; email: string[]; password: string[] };

export const NO_LOGIN_ERRORS: LoginErrors = { form: [], email: [], password: [] };

export const LOGIN_FAILED = '로그인하지 못했습니다. 잠시 후 다시 시도해 주세요.';

/** 429 `AUTH_040` — 같은 이메일의 관리자 로그인 시도가 창(BE 기본 15분)의 허용 횟수를 넘었다(#66) */
export const LOGIN_RATE_LIMITED = '로그인 시도가 너무 많습니다.';

/** 503 `AUTH_013` — 시도 계수기(저장소) 장애로 로그인을 받지 않는다(fail-closed). 입력이 틀린 게 아니다 */
export const LOGIN_STORE_UNAVAILABLE =
  '로그인 확인이 잠시 원활하지 않습니다. 입력은 그대로 두고 잠시 뒤에 다시 시도해 주세요.';

/**
 * 관리자 로그인 실패를 화면 문구로(#204). 어드민은 국문만이라 서버 문구를 그대로 쓴다.
 * - 400 `AUTH_030` — 이메일 없음 · 비밀번호 틀림 · 고객 계정이 모두 같은 한 문구다(계정 존재 여부를 흘리지 않게). 칸을 짚지 않는다.
 * - 필드 검증(`AUTH_130~132`) — 이메일 · 비밀번호 칸에 붙인다. 그 밖의 칸은 폼 전체 한 문구다.
 * - 요청 제한(429) — 이유(`AUTH_040` 로그인 시도 제한, 그 밖은 일반 요청 제한)와 남은 시간을 담은 안내(#407).
 *   버튼은 `useLoginCooldown` 이 그 시간 동안 막고 남은 시간을 센다.
 * - 503 `AUTH_013`(시도 계수기 장애) — 입력 탓이 아니니 잠시 뒤 다시 시도 안내.
 * - 그 밖(연결 · 일시 장애 · 예상 밖) — 한 문구로 다시 시도를 권한다.
 */
export function loginErrors(cause: unknown): LoginErrors {
  if (!(cause instanceof ApiError)) return { ...NO_LOGIN_ERRORS, form: [LOGIN_FAILED] };
  if (cause.kind === 'rateLimited') {
    const reason = cause.code === 'AUTH_040' ? LOGIN_RATE_LIMITED : '요청이 많습니다.';
    const wait =
      cause.retryAfterSec !== null && cause.retryAfterSec > 0
        ? `${formatWait(cause.retryAfterSec)} 뒤에`
        : '잠시 뒤에';
    return { ...NO_LOGIN_ERRORS, form: [`${reason} ${wait} 다시 시도해 주세요.`] };
  }
  if (cause.code === 'AUTH_013') return { ...NO_LOGIN_ERRORS, form: [LOGIN_STORE_UNAVAILABLE] };
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
