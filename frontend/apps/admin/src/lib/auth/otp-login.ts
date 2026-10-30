import { ApiError } from '@cf/api-client';
import { formatWait } from './login-cooldown';
import { LOGIN_FAILED, LOGIN_STORE_UNAVAILABLE } from './login-errors';

/**
 * 관리자 로그인 2단계 챌린지(#414). 화면 메모리(state)에만 둔다 — 쿠키 · localStorage · sessionStorage · URL · 로그 · 분석 이벤트에 남기지 않는다.
 * `expiresAt` 은 받은 시각 + `challengeExpiresIn`(ms)이다.
 */
export type OtpChallenge = { token: string; expiresAt: number };

/** 1단계 BFF 성공 dataBody 가 OTP 2단계 요구면 챌린지를, 아니면(OTP 미등록 — 이미 쿠키가 심겼다) null 을 돌려준다 */
export function readOtpChallenge(dataBody: unknown, now: number = Date.now()): OtpChallenge | null {
  if (typeof dataBody !== 'object' || dataBody === null) return null;
  const value = dataBody as Record<string, unknown>;
  if (value.otpRequired !== true) return null;
  if (typeof value.challengeToken !== 'string' || value.challengeToken === '') return null;
  const sec = value.challengeExpiresIn;
  if (typeof sec !== 'number' || !Number.isInteger(sec) || sec <= 0) return null;
  return { token: value.challengeToken, expiresAt: now + sec * 1000 };
}

/** 챌린지 만료 · `AUTH_034` — 1단계부터 다시 */
export const OTP_CHALLENGE_EXPIRED =
  '인증 시간이 지났습니다. 이메일과 비밀번호부터 다시 로그인해 주세요.';
/** `AUTH_044` — 같은 관리자의 다른 OTP 작업과 겹쳤다. 챌린지가 이미 소비돼 1단계부터 다시 */
export const OTP_CONFLICT =
  '다른 인증 작업과 겹쳤습니다. 이메일과 비밀번호부터 다시 로그인해 주세요.';
/** `AUTH_035` — 틀림 · 이미 쓴 코드 · 형식 차이를 나누지 않는 한 문구(BE 와 같다) */
export const OTP_CODE_INVALID =
  '인증 코드가 올바르지 않거나 이미 사용되었습니다. 다시 입력해 주세요.';
/** 429 `AUTH_043` — 같은 관리자의 2단계 시도가 창(BE 기본 15분)의 허용 횟수를 넘었다 */
export const OTP_RATE_LIMITED = '인증 코드 시도가 너무 많습니다.';

/**
 * 2단계 실패를 화면 결과로 바꾼다.
 * - `restart` — 1단계(이메일 · 비밀번호)로 돌아가 이 문구를 보인다. 챌린지가 더는 쓸 수 없을 때다
 *   (`AUTH_034` 만료 · 이미 씀 · 그 사이 계정 변경, `AUTH_044` 쓰기 잠금 대기 초과, `AUTH_153` 챌린지 없음).
 * - `retry` — 같은 화면에서 다시 입력한다(`AUTH_035`, 코드 칸 검증 `AUTH_147` · `AUTH_149`, 429, 일시 장애).
 *   `code` 는 코드 칸에, `form` 은 버튼 위에 붙인다.
 */
export type OtpOutcome =
  { kind: 'restart'; notice: string } | { kind: 'retry'; code: string[]; form: string[] };

export function otpOutcome(cause: unknown): OtpOutcome {
  const retry = (code: string[], form: string[] = []): OtpOutcome => ({
    kind: 'retry',
    code,
    form,
  });
  if (!(cause instanceof ApiError)) return retry([], [LOGIN_FAILED]);
  if (cause.code === 'AUTH_034') return { kind: 'restart', notice: OTP_CHALLENGE_EXPIRED };
  if (cause.code === 'AUTH_044') return { kind: 'restart', notice: OTP_CONFLICT };
  if (cause.kind === 'rateLimited') {
    const reason = cause.code === 'AUTH_043' ? OTP_RATE_LIMITED : '요청이 많습니다.';
    const wait =
      cause.retryAfterSec !== null && cause.retryAfterSec > 0
        ? `${formatWait(cause.retryAfterSec)} 뒤에`
        : '잠시 뒤에';
    return retry([], [`${reason} ${wait} 다시 시도해 주세요.`]);
  }
  if (cause.code === 'AUTH_035') return retry([OTP_CODE_INVALID]);
  if (cause.code === 'AUTH_013') return retry([], [LOGIN_STORE_UNAVAILABLE]);
  if (cause.fieldErrors.length > 0) {
    if (cause.fieldErrors.some((item) => item.field === 'challengeToken')) {
      return { kind: 'restart', notice: OTP_CHALLENGE_EXPIRED };
    }
    const code = cause.fieldErrors
      .filter((item) => item.field === 'code')
      .map((item) => item.message);
    const other = cause.fieldErrors.some((item) => item.field !== 'code');
    return retry(code, other ? [LOGIN_FAILED] : []);
  }
  return retry([], [LOGIN_FAILED]);
}

/** 2단계 제출 버튼 문구 — 보내는 중 · 시도 제한 남은 시간 · 기본 */
export function otpSubmitLabel(submitting: boolean, remainingSec: number): string {
  if (submitting) return '확인하는 중…';
  if (remainingSec > 0) return `${formatWait(remainingSec)} 뒤에 다시 시도할 수 있습니다`;
  return '확인';
}
