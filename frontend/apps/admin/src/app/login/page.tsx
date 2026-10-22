import { brand } from '@cf/brand';
import type { Metadata } from 'next';
import { LoginForm } from '@/components/auth/LoginForm';
import { safeReturnPath } from '@/lib/auth/return-path';

export const metadata: Metadata = { title: '로그인' };

/**
 * 관리자 로그인(#204). 콘솔 화면 틀(`(console)`) 밖이다. `?next=` 는 로그인 뒤 돌아갈 콘솔 경로다
 * — 로그인 전 · 세션 만료로 `proxy.ts` 나 `AccessNotice` 가 붙여 보낸다.
 * 세션은 access 수명(기본 30분)이 전부라 만료되면 다시 로그인한다(BE #191 — refresh 없음).
 */
export default async function LoginPage({ searchParams }: PageProps<'/login'>) {
  const { next } = await searchParams;

  return (
    <main className="mx-auto flex min-h-dvh w-full max-w-sm flex-col justify-center gap-8 px-4 py-16">
      <h1 className="text-center text-xl font-semibold">{brand.name} 어드민</h1>
      <LoginForm returnPath={safeReturnPath(next)} />
    </main>
  );
}
