import type { Metadata } from 'next';
import { LoginForm } from '@/components/auth/LoginForm';
import { safeReturnPath } from '@/lib/auth/return-path';
import { getI18n, localeAlternates } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return {
    title: dict.auth.login.title,
    alternates: await localeAlternates('/login'),
    // 검색 결과에 로그인 화면을 내지 않는다
    robots: { index: false },
  };
}

/** 고객 로그인(#25). `?next=` 는 로그인 뒤 돌아갈 앱 경로다(언어 접두어 없이) */
export default async function LoginPage({ searchParams }: PageProps<'/[locale]/login'>) {
  const { dict } = await getI18n();
  const { next } = await searchParams;

  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-10 px-4 py-16 lg:py-24">
      <h1 className="text-center font-serif text-[1.75rem] leading-tight">
        {dict.auth.login.title}
      </h1>
      <LoginForm returnPath={safeReturnPath(next)} />
    </main>
  );
}
