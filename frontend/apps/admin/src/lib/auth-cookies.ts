import type { AuthCookieConfig } from '@cf/api-client/server';

/**
 * 어드민 인증 쿠키(#204). 고객 세션(`cf_at` · `cf_rt`)과 섞이지 않게 이름을 따로 둔다.
 * 관리자 로그인은 refresh 토큰을 주지 않는다(BE #191) — access 쿠키 하나가 세션의 전부이고 BFF 는 재발급하지 않는다.
 * 수명은 로그인 응답의 `accessTokenExpiresIn` 이고, 없을 때의 대체값은 BE 기본 access 수명(30분)과 같게 둔다.
 * 쿠키가 토큰과 같이 만료되므로 만료된 세션은 다음 화면 이동에서 `proxy.ts` 가 로그인 화면으로 보낸다.
 */
export const authCookies: AuthCookieConfig = {
  accessName: 'cf_admin_at',
  refreshName: null,
  accessMaxAgeSec: 60 * 30,
  secure: process.env.NODE_ENV === 'production',
};
