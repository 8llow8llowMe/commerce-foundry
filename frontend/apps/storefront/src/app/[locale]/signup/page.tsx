import type { Metadata } from 'next';
import { SignUpForm } from '@/components/auth/SignUpForm';
import { safeReturnPath } from '@/lib/auth/return-path';
import { getI18n, localeAlternates } from '@/lib/i18n/server';

export async function generateMetadata(): Promise<Metadata> {
  const { dict } = await getI18n();
  return {
    title: dict.auth.signUp.title,
    alternates: await localeAlternates('/signup'),
    // 검색 결과에 가입 화면을 내지 않는다
    robots: { index: false },
  };
}

/** 고객 회원가입(#25). 가입하면 바로 로그인된 채 `?next=`(언어 접두어 없는 앱 경로)로 돌아간다 */
export default async function SignUpPage({ searchParams }: PageProps<'/[locale]/signup'>) {
  const { dict } = await getI18n();
  const { next } = await searchParams;

  return (
    <main className="mx-auto flex w-full max-w-sm flex-col gap-10 px-4 py-16 lg:py-24">
      <h1 className="text-center font-serif text-[1.75rem] leading-tight">
        {dict.auth.signUp.title}
      </h1>
      <SignUpForm returnPath={safeReturnPath(next)} />
    </main>
  );
}
