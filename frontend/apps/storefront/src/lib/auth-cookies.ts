import type { AuthCookieConfig } from '@cf/api-client/server';

// 고객 인증 쿠키(#25). 수명은 로그인 응답의 *ExpiresIn 이 없을 때의 대체값이다 — BE 설정(access 30분 · refresh 14일)과 같게 둔다.
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 60 * 30,
  refreshMaxAgeSec: 60 * 60 * 24 * 14,
  secure: process.env.NODE_ENV === 'production',
};
