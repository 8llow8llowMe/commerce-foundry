import { createLoginHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 일반 BFF 프록시([...path])보다 구체적인 경로 — 로그인 응답의 토큰을 httpOnly 쿠키로 바꿔 심는다
export const POST = createLoginHandler(bffOptions);
export const dynamic = 'force-dynamic';
