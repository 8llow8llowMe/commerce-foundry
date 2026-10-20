import { createSignUpHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 일반 BFF 프록시([...path])보다 구체적인 경로 — 가입 뒤 이어서 로그인하고 토큰을 httpOnly 쿠키로 심는다
export const POST = createSignUpHandler(bffOptions);
export const dynamic = 'force-dynamic';
