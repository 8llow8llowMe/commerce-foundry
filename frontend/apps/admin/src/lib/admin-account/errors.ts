import { ApiError } from '@cf/api-client';
import {
  accessDeniedOf,
  fieldKey,
  SAVE_FAILED,
  SESSION_EXPIRED_ON_SAVE,
  type AccessDenied,
  type FormErrors,
} from '@/lib/api-errors';

/**
 * 비밀번호 규칙 — BE `AdminRequestPatterns.PASSWORD` · `@Size(12, 64)` 와 같다. 정본은 서버다.
 * 영문 · 숫자를 1자 이상씩 포함하고 공백 없는 ASCII(0x21~0x7E).
 */
export const PASSWORD_MIN = 12;
export const PASSWORD_MAX = 64;
const PASSWORD_PATTERN = /^(?=.*[A-Za-z])(?=.*[0-9])[\x21-\x7E]+$/;

export const PASSWORD_HINT = `${PASSWORD_MIN}~${PASSWORD_MAX}자, 영문과 숫자를 1자 이상씩 넣고 공백 없이 입력합니다.`;
export const PASSWORD_CONFIRM_MISMATCH = '새 비밀번호가 서로 다릅니다.';
export const DEACTIVATE_FAILED = '비활성화하지 못했습니다. 잠시 후 다시 시도해 주세요.';
/** 502 · 504 처럼 서버가 처리했는지 모르는 실패(`unknownOutcome`). 다시 보내기 전에 결과를 확인하게 한다 */
export const CREATE_UNKNOWN =
  '결과를 확인하지 못했습니다. 관리자 목록에서 추가됐는지 확인한 뒤 다시 시도해 주세요.';
export const PASSWORD_UNKNOWN =
  '결과를 확인하지 못했습니다. 비밀번호가 바뀌었을 수 있으니 로그아웃한 뒤 새 비밀번호로 로그인해 보세요.';
export const DEACTIVATE_UNKNOWN = '결과를 확인하지 못했습니다. 목록을 다시 불러왔습니다.';
/** 404 `AUTH_033` — 목록에 ADMIN 으로 있으니 사실상 이미 꺼진 계정이다(없음 · 고객과 구분하지 않는다) */
export const ALREADY_OFF = '이미 비활성화됐거나 찾을 수 없는 계정입니다.';
/**
 * 503 `AUTH_013` — BE 는 계정을 끈 뒤(커밋) 옛 토큰 컷오프(Redis)를 하므로, 이 코드면 계정은 이미 꺼졌고 기존 로그인만 못 끊었다.
 * 서버 문구("로그인을 처리할 수 없습니다")는 이 상황과 맞지 않아 화면 문구를 쓴다.
 */
export const OFF_BUT_SESSION_LIVE =
  '비활성화했지만 이 계정의 기존 로그인을 끊지 못했습니다. 최대 30분 동안은 쓸 수 있습니다.';

/**
 * 보내기 전 비밀번호 확인. 문구는 서버(`AUTH_141` · `AUTH_142`)와 같다. 빈 칸은 서버 필수 검증에 맡긴다.
 * 비밀번호 값은 돌려주지도, 문구에 넣지도 않는다.
 */
export function passwordProblem(password: string): string | null {
  if (password === '') return null;
  if (password.length < PASSWORD_MIN || password.length > PASSWORD_MAX) {
    return `비밀번호는 ${PASSWORD_MIN}자 이상 ${PASSWORD_MAX}자 이하여야 합니다.`;
  }
  if (!PASSWORD_PATTERN.test(password)) {
    return '비밀번호는 영문과 숫자를 1자 이상씩 포함하고, 공백 없이 영문 · 숫자 · 특수문자로만 입력해 주세요.';
  }
  return null;
}

export type SubmitFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** `uncertain` — 서버가 처리했는지 모른다(`unknownOutcome`). 화면은 결과를 다시 확인하게 한다 */
  | { kind: 'errors'; errors: FormErrors; uncertain?: true };

/**
 * 폼 저장 실패 → 칸 오류(§9). fieldErrors 는 `fieldKey` 로 칸에, 칸으로 정한 도메인 코드는 그 칸에 서버 문구로,
 * 그 밖의 4xx 는 폼 전체 서버 문구, 연결 · 5xx · 예상 밖은 `SAVE_FAILED` 한 문구다.
 * 401 은 입력을 지키려 `SESSION_EXPIRED_ON_SAVE`, 403 은 권한 안내(`access`).
 */
function submitFailure(
  cause: unknown,
  fields: ReadonlySet<string>,
  codeFields: Readonly<Record<string, string>>,
  unknownMessage: string,
): SubmitFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'forbidden') return { kind: 'access', denied };
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [SESSION_EXPIRED_ON_SAVE] } };
  if (!(cause instanceof ApiError)) return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'errors', errors: { form: [unknownMessage] }, uncertain: true };

  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const key = fieldKey(item.field);
      // 폼이 그리지 않는 칸은 폼 전체 한 문구로 모은다
      const target = fields.has(key) ? key : 'form';
      const message = target === 'form' ? SAVE_FAILED : item.message;
      if (!(errors[target] ??= []).includes(message)) errors[target].push(message);
    }
    return { kind: 'errors', errors };
  }
  const field = cause.code ? codeFields[cause.code] : undefined;
  if (field) return { kind: 'errors', errors: { [field]: [cause.message] } };
  if (cause.status !== null && cause.status >= 400 && cause.status < 500 && cause.code) {
    return { kind: 'errors', errors: { form: [cause.message] } };
  }
  return { kind: 'errors', errors: { form: [SAVE_FAILED] } };
}

const CREATE_FIELDS = new Set(['email', 'password', 'name']);

/**
 * 관리자 추가 실패. 409 `AUTH_002`(이미 있는 이메일 — 고객 포함, 승격하지 않는다)는 이메일 칸에.
 * 칸 키는 BE 본문 이름(`email` · `password` · `name`)이다 — 폼 input 이름(`staffEmail` · `initialPassword`)과 다르다.
 */
export function createAdminFailure(cause: unknown): SubmitFailure {
  return submitFailure(cause, CREATE_FIELDS, { AUTH_002: 'email' }, CREATE_UNKNOWN);
}

const PASSWORD_FIELDS = new Set(['currentPassword', 'newPassword']);

/**
 * 내 비밀번호 변경 실패. 400 `AUTH_032`(현재 비밀번호 틀림)는 현재 비밀번호 칸에.
 * 503 `AUTH_013` 은 실패가 아니다 — BE 가 새 비밀번호를 저장(커밋)한 뒤 옛 토큰 컷오프에서 났으므로 `changed` 로 돌려
 * 성공처럼 로그아웃(BFF 가 access 를 막고 쿠키를 지운다) · 로그인 화면으로 보낸다. 실패로 두면 같은 값을 다시 보내 `AUTH_032` 를 받는다.
 */
export function changePasswordFailure(cause: unknown): SubmitFailure | { kind: 'changed' } {
  if (cause instanceof ApiError && cause.code === 'AUTH_013') return { kind: 'changed' };
  return submitFailure(cause, PASSWORD_FIELDS, { AUTH_032: 'currentPassword' }, PASSWORD_UNKNOWN);
}

export type DeactivateFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 줄에 붙이는 문구. `off` 면 그 계정은 꺼진 것으로 표시한다(목록에 활성 칸이 없어 다시 읽어도 모른다). `reload` 면 목록을 다시 읽는다 */
  | { kind: 'row'; message: string; reload: boolean; off?: true };

/**
 * 비활성화 실패. 409 `AUTH_031`(마지막 관리자) · 400 `AUTH_146` 은 서버 문구.
 * 404 `AUTH_033` 과 503 `AUTH_013`(끈 뒤 컷오프 실패)은 꺼진 것으로 표시한다. 연결 실패 · 결과 모름은 목록을 다시 읽는다.
 * 401 은 세션 끝 — 목록처럼 로그인으로 보낸다(추가 폼이 열린 동안에는 줄 버튼을 막아 입력을 잃지 않는다).
 */
export function deactivateFailure(cause: unknown): DeactivateFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError))
    return { kind: 'row', message: DEACTIVATE_FAILED, reload: true };
  if (cause.kind === 'unknownOutcome')
    return { kind: 'row', message: DEACTIVATE_UNKNOWN, reload: true };
  if (cause.code === 'AUTH_031' || cause.code === 'AUTH_146') {
    return { kind: 'row', message: cause.message, reload: false };
  }
  if (cause.code === 'AUTH_033')
    return { kind: 'row', message: ALREADY_OFF, reload: false, off: true };
  if (cause.code === 'AUTH_013') {
    return { kind: 'row', message: OFF_BUT_SESSION_LIVE, reload: false, off: true };
  }
  return { kind: 'row', message: DEACTIVATE_FAILED, reload: true };
}
