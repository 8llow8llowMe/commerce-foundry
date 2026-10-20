import { createLogoutHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 일반 BFF 프록시([...path])보다 구체적인 경로 — httpOnly refresh 쿠키를 본문에 실어 로그아웃하고 쿠키를 지운다
export const POST = createLogoutHandler(bffOptions);
export const dynamic = 'force-dynamic';
