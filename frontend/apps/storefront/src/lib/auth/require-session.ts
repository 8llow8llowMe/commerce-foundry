import { redirect } from 'next/navigation';
import { getI18n } from '@/lib/i18n/server';
import { hasSession } from './session';

/**
 * 회원 화면(마이페이지 등)의 진입 확인. 인증 쿠키가 없으면 로그인 화면으로 보내고, 로그인 뒤 `path`(언어 없는 앱 경로)로 돌아온다.
 * 쿠키가 있어도 세션이 끝났을 수 있다 — 그 경우는 화면의 API 호출이 401 을 받아 다시 로그인을 안내한다.
 */
export async function requireSession(path: string): Promise<void> {
  if (await hasSession()) return;
  const { href } = await getI18n();
  redirect(href(`/login?next=${encodeURIComponent(path)}`));
}
