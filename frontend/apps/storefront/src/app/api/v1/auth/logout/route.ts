import { createLogoutHandler } from '@cf/api-client/server';
import { authCookies } from '@/lib/auth-cookies';
import { gatewayUrl } from '@/lib/env';

// 일반 BFF 프록시([...path])보다 구체적인 경로 — httpOnly refresh 쿠키를 본문에 실어 로그아웃하고 쿠키를 지운다
export const POST = createLogoutHandler({ gatewayUrl, cookies: authCookies });
export const dynamic = 'force-dynamic';
