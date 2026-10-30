import { ApiError } from '@cf/api-client';
import { encode } from 'uqr';
import { formatWait } from '@/lib/auth/login-cooldown';
import {
  accessDeniedOf,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';

/**
 * 관리자 본인 2단계 인증(OTP, #417 · BE #238 PR-A2). 정본은 `backend/docs/api-design-guide.md` "관리자 본인 OTP".
 * 비밀 키 · 복구 코드는 화면 상태에만 둔다 — 브라우저 저장소 · 주소 · 로그에 남기지 않는다.
 */
export const OTP_PATH = '/api/v1/admin/me/otp';
export const OTP_ENROLLMENT_PATH = `${OTP_PATH}/enrollment`;
export const OTP_DEACTIVATION_PATH = `${OTP_PATH}/deactivation`;
export const OTP_RECOVERY_CODES_PATH = `${OTP_PATH}/recovery-codes`;

/** `GET /api/v1/admin/me/otp`. 확인 전 진행 중 등록은 `enrolled=false` 다 */
export type OtpStatus = {
  enrolled: boolean;
  confirmedAt: string | null;
  remainingRecoveryCodes: number;
};

/** `POST …/enrollment` — 비밀은 이번 한 번만 온다 */
export type OtpEnrollment = { secret: string; otpauthUri: string; expiresAt: string };

/** 등록 확인(201) · 복구 코드 재발급(200) 응답 — 원문은 이번 한 번만 온다 */
export type RecoveryCodes = { recoveryCodes: string[] };

/** 등록 확인 · 해제 · 재발급의 대상 — 오류 문구와 결과를 모를 때의 안내가 다르다 */
export type OtpAction = 'start' | 'confirm' | 'deactivate' | 'regenerate';

/** 남은 복구 코드가 이 수 이하이면 새로 받으라고 안내한다 */
export const LOW_RECOVERY_CODES = 2;

export const OTP_FAILED = '처리하지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const OTP_TOO_MANY = '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.';
export const OTP_CHECK_INPUT = '입력값을 확인해 주세요.';
export const OTP_CURRENT_PASSWORD_REQUIRED = '현재 비밀번호를 입력해 주세요.';
export const OTP_WRONG_PASSWORD = '현재 비밀번호가 맞지 않습니다.';
export const OTP_CODE_REQUIRED = '인증 코드를 입력해 주세요.';
export const OTP_SIX_DIGITS = '인증 앱에 보이는 숫자 6자리를 입력해 주세요.';
export const OTP_CODE_OR_RECOVERY = '인증 앱의 숫자 6자리 또는 복구 코드를 입력해 주세요.';
export const OTP_WRONG_CODE =
  '인증 코드가 맞지 않거나 이미 쓴 코드입니다. 인증 앱의 새 코드를 입력해 주세요.';
export const OTP_WRONG_CODE_OR_RECOVERY =
  '인증 코드 또는 복구 코드가 맞지 않거나 이미 쓴 코드입니다.';
/** 409 `AUTH_044` — 같은 관리자의 다른 2단계 인증 요청과 겹쳤다. 코드는 쓰이지 않았으니 같은 내용으로 다시 보내면 된다 */
export const OTP_BUSY =
  '다른 요청과 겹쳐 처리하지 못했습니다. 잠시 후 같은 내용으로 다시 시도해 주세요.';
/** 404 `AUTH_033` — 토큰의 관리자 계정이 없다(그 사이 비활성화 등) */
export const OTP_ACCOUNT_GONE =
  '관리자 계정을 찾을 수 없습니다. 로그아웃한 뒤 다시 로그인해 주세요.';
export const OTP_ALREADY_OFF = '2단계 인증이 이미 꺼져 있습니다.';
export const OTP_ALREADY_ON = '이미 2단계 인증을 쓰고 있습니다.';
export const OTP_ENROLLMENT_EXPIRED =
  '등록 시간이 지났거나 다른 곳에서 등록을 다시 시작했습니다. 처음부터 다시 등록해 주세요.';
/** 503 `AUTH_039` — 서버가 저장된 인증 정보를 읽지 못한다(설정 · 손상). 운영자가 고칠 수 없다 */
export const OTP_UNREADABLE =
  '지금은 2단계 인증을 처리할 수 없습니다. 계속되면 시스템 담당자에게 알려 주세요.';

const UNKNOWN_OUTCOME: Record<OtpAction, string> = {
  start: '결과를 확인하지 못했습니다. 다시 시작해 주세요.',
  confirm:
    '결과를 확인하지 못했습니다. 상태를 다시 불러왔습니다. 등록됐다면 복구 코드를 다시 받아 보관해 주세요.',
  deactivate: '결과를 확인하지 못했습니다. 상태를 다시 불러왔습니다.',
  regenerate: '결과를 확인하지 못했습니다. 복구 코드가 바뀌었을 수 있으니 다시 받아 보관해 주세요.',
};

/** 검증 코드 → 칸 · 운영자 문구(#305 — 서버 검증 문구는 그대로 보이지 않는다) */
const FIELD_CODES: Readonly<Record<string, { field: string; message: string }>> = {
  AUTH_145: { field: 'currentPassword', message: OTP_CURRENT_PASSWORD_REQUIRED },
  AUTH_147: { field: 'code', message: OTP_CODE_REQUIRED },
  AUTH_148: { field: 'code', message: OTP_SIX_DIGITS },
  AUTH_149: { field: 'code', message: OTP_CODE_OR_RECOVERY },
};

export type OtpFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 429 — 화면은 `Retry-After` 동안 확인 버튼을 막는다(자동 재시도 없음) */
  | { kind: 'rateLimited' }
  /**
   * 칸 · 폼 오류. `next` 가 있으면 지금 단계를 접는다 — `reload` 는 상태를 다시 읽어 첫 화면으로(문구는 거기에),
   * `restart` 는 진행 중 등록이 사라졌으니 첫 화면에서 다시 시작하게 한다.
   */
  | { kind: 'errors'; errors: FormErrors; next?: 'reload' | 'restart' };

const form = (message: string, next?: 'reload' | 'restart'): OtpFailure =>
  next
    ? { kind: 'errors', errors: { form: [message] }, next }
    : { kind: 'errors', errors: { form: [message] } };

/**
 * 2단계 인증 요청 실패 → 화면 처리. 401 은 입력을 지키는 폼 오류, 403 은 권한 안내다(다른 어드민 폼과 같다).
 * 비밀번호(`AUTH_032`) · 코드(`AUTH_035`) 틀림은 그 칸에, 검증 코드는 칸별 운영자 문구로.
 */
export function otpFailure(cause: unknown, action: OtpAction): OtpFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'forbidden') return { kind: 'access', denied };
  if (denied === 'unauthorized') return form(SESSION_EXPIRED_ON_SAVE);
  if (!(cause instanceof ApiError)) return form(OTP_FAILED);
  if (cause.kind === 'rateLimited') return { kind: 'rateLimited' };
  if (cause.kind === 'unknownOutcome') {
    return form(UNKNOWN_OUTCOME[action], action === 'start' ? undefined : 'reload');
  }

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const known = FIELD_CODES[item.code];
      const target = known?.field ?? 'form';
      const message = known?.message ?? OTP_CHECK_INPUT;
      if (!(errors[target] ??= []).includes(message)) errors[target].push(message);
    }
    return { kind: 'errors', errors };
  }

  switch (cause.code) {
    case 'AUTH_032':
      return { kind: 'errors', errors: { currentPassword: [OTP_WRONG_PASSWORD] } };
    case 'AUTH_035':
      return {
        kind: 'errors',
        errors: { code: [action === 'confirm' ? OTP_WRONG_CODE : OTP_WRONG_CODE_OR_RECOVERY] },
      };
    case 'AUTH_044':
      return form(OTP_BUSY);
    case 'AUTH_033':
      return form(OTP_ACCOUNT_GONE);
    case 'AUTH_036':
      return form(OTP_ALREADY_OFF, 'reload');
    case 'AUTH_037':
      return form(OTP_ALREADY_ON, 'reload');
    case 'AUTH_038':
      return form(OTP_ENROLLMENT_EXPIRED, 'restart');
    case 'AUTH_039':
      return form(OTP_UNREADABLE);
    default: {
      const code = cause.code ? FIELD_CODES[cause.code] : undefined;
      if (code) return { kind: 'errors', errors: { [code.field]: [code.message] } };
      return form(OTP_FAILED);
    }
  }
}

/** 429 안내 — 남은 시간은 1분 이상이면 분으로 올린다 */
export function otpRateLimitText(remainingSec: number): string {
  return `확인 시도가 너무 많습니다. ${formatWait(remainingSec)} 뒤에 다시 시도해 주세요.`;
}

/** 입력한 코드에서 공백을 지운다(`123 456` 처럼 띄어 써도 받는다). 복구 코드의 하이픈은 서버가 다룬다 */
export function normalizeOtpCode(raw: string): string {
  return raw.replace(/\s+/g, '');
}

/** 등록 확인 코드 확인 — 숫자 6자리만(BE 148). 빈 칸은 서버 필수 검증에 맡긴다 */
export function totpProblem(code: string): string | null {
  if (code === '') return null;
  return /^\d{6}$/.test(code) ? null : OTP_SIX_DIGITS;
}

/** 해제 · 재발급 코드 확인 — 숫자면 6자리, 아니면 복구 코드로 보고 32자 이하(BE 149) */
export function reauthCodeProblem(code: string): string | null {
  if (code === '') return null;
  if (/^\d+$/.test(code)) return code.length === 6 ? null : OTP_SIX_DIGITS;
  return code.length <= 32 ? null : OTP_CODE_OR_RECOVERY;
}

/** 직접 입력용 키 — 4자씩 띄어 보인다(인증 앱은 공백을 무시한다) */
export function formatOtpSecret(secret: string): string {
  return secret.match(/.{1,4}/g)?.join(' ') ?? secret;
}

/** 복사할 복구 코드 — 한 줄에 하나 */
export function recoveryCodesText(codes: readonly string[]): string {
  return codes.join('\n');
}

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});
const time = new Intl.DateTimeFormat('ko-KR', { timeStyle: 'short', timeZone: 'Asia/Seoul' });

/** 등록일 — 한국 시각. 없거나 읽을 수 없으면 null */
export function formatOtpDate(value: string | null): string | null {
  if (value === null) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : dateTime.format(date);
}

/** 등록 마감 시각 — 한국 시각(시:분). 읽을 수 없으면 null */
export function formatOtpDeadline(value: string): string | null {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : time.format(date);
}

/**
 * otpauth URI → QR 칸 그림(SVG path, 한 칸 = 1). 여백(quiet zone) 4칸을 포함한다.
 * 정정 수준은 M — 화면에서 찍으므로 L 보다 조금 튼튼하게.
 */
export function qrPath(value: string): { size: number; d: string } {
  const { size, data } = encode(value, { ecc: 'M', border: 4 });
  let d = '';
  data.forEach((row, y) => {
    row.forEach((dark, x) => {
      if (dark) d += `M${x} ${y}h1v1h-1z`;
    });
  });
  return { size, d };
}
