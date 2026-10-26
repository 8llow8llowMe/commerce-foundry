/**
 * 로그인 화면 위 안내(#352). 주소에는 사유 이름만 싣는다 — 문구 · 값은 싣지 않는다(손으로 고친 주소로 아무 글이나 띄우지 못하게).
 */
const NOTICES = {
  'password-changed': '비밀번호를 바꿨습니다. 새 비밀번호로 다시 로그인해 주세요.',
} as const;

/** 비밀번호를 바꾼 뒤 가는 로그인 화면. 돌아갈 경로(`next`)는 붙이지 않는다 — 콘솔 홈으로 */
export const PASSWORD_CHANGED_LOGIN = '/login?reason=password-changed';

/** `?reason=` → 안내 문구. 모르는 값은 null */
export function loginNotice(reason: string | string[] | undefined): string | null {
  const value = Array.isArray(reason) ? reason[0] : reason;
  return value !== undefined && Object.hasOwn(NOTICES, value)
    ? NOTICES[value as keyof typeof NOTICES]
    : null;
}
