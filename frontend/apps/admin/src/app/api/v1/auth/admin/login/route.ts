import { createAdminLoginHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 일반 BFF 프록시([...path])보다 구체적인 경로 — 응답의 access 토큰을 httpOnly 쿠키로 바꿔 심는다(브라우저에는 토큰을 주지 않는다)
export const POST = createAdminLoginHandler(bffOptions);
export const dynamic = 'force-dynamic';
