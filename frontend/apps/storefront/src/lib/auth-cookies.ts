import type { AuthCookieConfig } from '@cf/api-client/server';

// 쿠키 이름·수명은 #25 에서 BE 토큰 만료와 맞춰 확정한다. 지금 값은 임시다.
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_at',
  refreshName: 'cf_rt',
  accessMaxAgeSec: 60 * 30,
  refreshMaxAgeSec: 60 * 60 * 24 * 14,
  secure: process.env.NODE_ENV === 'production',
};
