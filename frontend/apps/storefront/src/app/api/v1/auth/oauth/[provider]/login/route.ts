import { createSocialLoginHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 일반 BFF 프록시([...path])는 이 경로를 막는다 — 소셜 로그인 응답의 토큰을 httpOnly 쿠키로 바꿔 심는다(kakao · naver 만)
export const POST = createSocialLoginHandler(bffOptions);
export const dynamic = 'force-dynamic';
