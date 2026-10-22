import { NextResponse, type NextRequest } from 'next/server';
import { authCookies } from '@/lib/auth-cookies';
import { loginHref } from '@/lib/auth/return-path';

/**
 * 콘솔 진입 확인(#204). access 쿠키가 없으면(로그인 전 · 만료 — 쿠키 수명이 토큰 수명이다) 로그인 화면으로 보내고,
 * 로그인 뒤 지금 경로로 돌아온다. 토큰이 유효한지는 보지 않는다 — 쿠키가 있어도 세션이 끝났을 수 있고(로그아웃 · 차단),
 * 그때는 화면의 API 가 401 을 받아 `AccessNotice` 가 로그인 화면으로 보낸다. 권한의 정본은 게이트웨이 · BE 다.
 */
export function proxy(request: NextRequest) {
  if (request.cookies.has(authCookies.accessName)) return NextResponse.next();
  const { pathname, search } = request.nextUrl;
  return NextResponse.redirect(new URL(loginHref(pathname + search), request.url));
}

export const config = {
  // BFF(/api) · Next 내부(/_next) · 확장자가 있는 정적 파일 · 로그인 화면은 로그인 없이 연다
  matcher: ['/((?!api/|api$|_next/|login$|.*\\..*).*)'],
};
