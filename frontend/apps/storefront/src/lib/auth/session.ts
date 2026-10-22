import { cookies } from 'next/headers';
import { authCookies } from '@/lib/auth-cookies';

/**
 * 헤더 · 메뉴의 로그인 진입점을 고르는 용도로만 쓴다 — 인증 쿠키가 있으면 로그인한 것으로 본다.
 * 토큰이 유효한지는 보지 않는다(만료된 access 는 BFF 가 refresh 로 재발급한다). 권한이 걸린 화면은 API 응답(401)으로 판단한다.
 * RSC 는 쿠키를 쓰지 못하므로 여기서 지우거나 재발급하지 않는다.
 */
export async function hasSession(): Promise<boolean> {
  const store = await cookies();
  const { accessName, refreshName } = authCookies;
  return store.has(accessName) || (refreshName !== null && store.has(refreshName));
}
