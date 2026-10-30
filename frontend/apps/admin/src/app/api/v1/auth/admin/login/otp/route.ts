import { createAdminLoginOtpHandler } from '@cf/api-client/server';
import { bffOptions } from '@/lib/bff-options';

// 관리자 로그인 2단계(#414) — 응답의 access 토큰을 httpOnly 쿠키로 바꿔 심는다(브라우저에는 토큰을 주지 않는다)
export const POST = createAdminLoginOtpHandler(bffOptions);
export const dynamic = 'force-dynamic';
