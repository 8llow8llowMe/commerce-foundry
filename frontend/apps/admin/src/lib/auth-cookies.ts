import type { AuthCookieConfig } from '@cf/api-client/server';

// 고객 세션과 섞이지 않도록 이름을 따로 둔다. 이름·수명은 #29 에서 확정한다. 지금 값은 임시다.
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_admin_at',
  refreshName: 'cf_admin_rt',
  accessMaxAgeSec: 60 * 30,
  refreshMaxAgeSec: 60 * 60 * 12,
  secure: process.env.NODE_ENV === 'production',
};
